package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Bounded dispatch hints only. The selected mature parser must validate the complete format. */
public final class JavaAudioProbe {
  public enum Container {
    WAV,
    FLAC,
    OGG,
    ISO_BMFF,
    MPEG_AUDIO,
    ADTS,
    MPEG_TS,
    MATROSKA,
    ASF,
    APE,
    WAVPACK,
    AIFF,
    AU,
    M3U_CANDIDATE,
    PLS_CANDIDATE,
    URL_PLAYLIST_CANDIDATE,
    UNKNOWN
  }

  /** Owns the supplied stream. Closing this result closes that stream exactly once. */
  public static final class PreparedInput implements AutoCloseable {
    private final Container container;
    private final InputStream input;
    private final long skippedMetadataBytes;
    private boolean closed;

    private PreparedInput(Container container, InputStream input, long skippedMetadataBytes) {
      this.container = container;
      this.input = input;
      this.skippedMetadataBytes = skippedMetadataBytes;
    }

    public Container container() {
      return container;
    }

    public InputStream input() {
      return input;
    }

    public long skippedMetadataBytes() {
      return skippedMetadataBytes;
    }

    @Override
    public synchronized void close() throws IOException {
      if (!closed) {
        closed = true;
        input.close();
      }
    }
  }

  public static final long MAX_ID3_BYTES = 16L * 1024 * 1024;
  private static final int MAX_PEEK = 512;
  private static final byte[] ASF = {
    0x30,
    0x26,
    (byte) 0xb2,
    0x75,
    (byte) 0x8e,
    0x66,
    (byte) 0xcf,
    0x11,
    (byte) 0xa6,
    (byte) 0xd9,
    0,
    (byte) 0xaa,
    0,
    0x62,
    (byte) 0xce,
    0x6c
  };

  private JavaAudioProbe() {}

  /**
   * Ownership transfers on entry, including failure. Does not use mark/reset or open another URL.
   */
  public static PreparedInput prepare(InputStream ownedInput) throws IOException {
    Objects.requireNonNull(ownedInput, "ownedInput");
    PushbackInputStream input = new PushbackInputStream(ownedInput, MAX_PEEK);
    try {
      long skipped = 0;
      int tags = 0;
      while (true) {
        Peek peek = new Peek(input);
        peek.ensure(4);
        if (!peek.starts("ID3")) {
          Container container = detect(peek);
          peek.replay();
          PreparedInput result = new PreparedInput(container, input, skipped);
          return result;
        }
        if (++tags > 16) throw new IOException("Too many ID3 tags");
        if (!peek.ensure(10)) throw new IOException("Truncated ID3 header");
        int version = peek.u(3), revision = peek.u(4), flags = peek.u(5);
        int allowed = version == 2 ? 0xc0 : version == 3 ? 0xe0 : version == 4 ? 0xf0 : -1;
        if (allowed < 0 || revision == 255 || (flags & ~allowed) != 0) {
          throw new IOException("Invalid ID3 header");
        }
        long size = 0;
        for (int i = 6; i < 10; i++) {
          if ((peek.u(i) & 0x80) != 0) throw new IOException("Invalid ID3 synchsafe size");
          size = (size << 7) | peek.u(i);
        }
        long total = 10 + size + (version == 4 && (flags & 0x10) != 0 ? 10 : 0);
        if (total > MAX_ID3_BYTES - skipped) throw new IOException("ID3 metadata exceeds limit");
        skipExactly(input, size);
        if (version == 4 && (flags & 0x10) != 0) {
          byte[] footer = new byte[10];
          for (int i = 0; i < footer.length; i++) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated ID3 footer");
            footer[i] = (byte) value;
          }
          if (footer[0] != '3' || footer[1] != 'D' || footer[2] != 'I') {
            throw new IOException("Invalid ID3 footer");
          }
          for (int i = 3; i < 10; i++) {
            if (footer[i] != peek.bytes[i]) throw new IOException("ID3 footer/header mismatch");
          }
        }
        skipped += total;
      }
    } catch (IOException | RuntimeException | Error failure) {
      try {
        input.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private static Container detect(Peek p) throws IOException {
    if (p.starts("fLaC")) return Container.FLAC;
    if (p.starts("OggS")) return Container.OGG;
    if (p.starts("wvpk")) return Container.WAVPACK;
    if (p.starts("MAC ")) return Container.APE;
    if (p.starts(".snd")) return Container.AU;
    if (p.starts(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3})) return Container.MATROSKA;
    if (p.starts("RIFF"))
      return p.ensure(12) && p.at("WAVE", 8) ? Container.WAV : Container.UNKNOWN;
    if (p.starts("FORM"))
      return p.ensure(12) && (p.at("AIFF", 8) || p.at("AIFC", 8))
          ? Container.AIFF
          : Container.UNKNOWN;
    if (p.starts(new byte[] {0x30, 0x26, (byte) 0xb2, 0x75})) {
      if (p.ensure(16) && p.starts(ASF)) return Container.ASF;
      return Container.UNKNOWN;
    }
    if (p.size >= 2 && p.u(0) == 0xff) {
      if (adtsPrefix(p)) {
        return p.ensure(7) && validAdts(p) && p.ensure((p.u(1) & 1) == 1 ? 7 : 9)
            ? Container.ADTS
            : Container.UNKNOWN;
      }
      return p.size >= 4 && validMpeg(p) ? Container.MPEG_AUDIO : Container.UNKNOWN;
    }
    if (p.size > 0 && p.u(0) == 0x47) {
      if (!p.ensure(380)) return Container.UNKNOWN;
      for (int offset : new int[] {0, 188, 376}) {
        if (p.u(offset) != 0x47 || (p.u(offset + 3) & 0x30) == 0) return Container.UNKNOWN;
      }
      return Container.MPEG_TS;
    }
    // A box size is unsigned; do not restrict major brands or the first size byte.
    if (p.size >= 4) {
      if (p.ensure(8)
          && (p.at("ftyp", 4)
              || p.at("moov", 4)
              || p.at("moof", 4)
              || p.at("styp", 4)
              || p.at("free", 4)
              || p.at("skip", 4)
              || p.at("wide", 4))) {
        long size = 0;
        for (int i = 0; i < 4; i++) size = (size << 8) | p.u(i);
        if (size == 1) {
          if (!p.ensure(16) || (p.u(8) & 0x80) != 0) return Container.UNKNOWN;
          long extended = 0;
          for (int i = 8; i < 16; i++) extended = (extended << 8) | p.u(i);
          if (extended < 16) return Container.UNKNOWN;
        } else if (size != 0 && size < 8) return Container.UNKNOWN;
        return Container.ISO_BMFF;
      }
    }
    // BOM and whitespace are permitted only in text candidates. No network or recursive resolution
    // here.
    int offset = 0;
    if (p.size >= 3 && p.u(0) == 0xef && p.u(1) == 0xbb && p.u(2) == 0xbf) offset = 3;
    while (offset < 128) {
      if (!p.ensure(offset + 1)) return Container.UNKNOWN;
      int b = p.u(offset);
      if (b != ' ' && b != '\r' && b != '\n' && b != '\t') break;
      offset++;
    }
    if (offset == 128) return Container.UNKNOWN;
    if (!p.ensure(offset + 1)) return Container.UNKNOWN;
    int first = p.u(offset);
    if (first != '#'
        && first != '['
        && first != 'h'
        && first != 'i'
        && first != 'H'
        && first != 'I') return Container.UNKNOWN;
    int needed = first == '#' ? 8 : first == '[' ? 10 : first == 'i' || first == 'I' ? 6 : 8;
    p.ensure(offset + needed);
    String text =
        new String(p.bytes, offset, p.size - offset, StandardCharsets.US_ASCII)
            .toLowerCase(Locale.ROOT);
    if (text.startsWith("#extm3u") || text.startsWith("#extinf") || text.startsWith("#ext-x-"))
      return Container.M3U_CANDIDATE;
    if (text.startsWith("[playlist]")) return Container.PLS_CANDIDATE;
    if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("icy://"))
      return Container.URL_PLAYLIST_CANDIDATE;
    return Container.UNKNOWN;
  }

  private static boolean adtsPrefix(Peek p) {
    return (p.u(1) & 0xf6) == 0xf0;
  }

  private static boolean validAdts(Peek p) {
    int frequencyIndex = (p.u(2) >>> 2) & 15;
    int channels = ((p.u(2) & 1) << 2) | (p.u(3) >>> 6);
    int length = ((p.u(3) & 3) << 11) | (p.u(4) << 3) | (p.u(5) >>> 5);
    int header = (p.u(1) & 1) == 1 ? 7 : 9;
    // PCE channel_configuration=0 is legal; the decoder validates the actual PCE.
    return frequencyIndex <= 12 && channels <= 7 && length >= header;
  }

  private static boolean validMpeg(Peek p) {
    int second = p.u(1), third = p.u(2), fourth = p.u(3);
    int version = (second >>> 3) & 3, layer = (second >>> 1) & 3;
    int bitrate = third >>> 4, frequency = (third >>> 2) & 3;
    // Bitrate 0 is legal free-format; maintained MPEG parser owns its frame-boundary validation.
    return (second & 0xe0) == 0xe0
        && version != 1
        && layer != 0
        && bitrate != 15
        && frequency != 3
        && (fourth & 3) != 2;
  }

  private static void skipExactly(InputStream input, long remaining) throws IOException {
    byte[] discard = new byte[8192];
    while (remaining > 0) {
      int n = input.read(discard, 0, (int) Math.min(discard.length, remaining));
      if (n < 0) throw new IOException("Truncated ID3 metadata");
      if (n == 0) {
        if (input.read() < 0) throw new IOException("Truncated ID3 metadata");
        remaining--;
      } else remaining -= n;
    }
  }

  private static final class Peek {
    private final PushbackInputStream input;
    private final byte[] bytes = new byte[MAX_PEEK];
    private int size;
    private boolean eof;

    private Peek(PushbackInputStream input) {
      this.input = input;
    }

    private int u(int offset) {
      return bytes[offset] & 255;
    }

    private boolean ensure(int needed) throws IOException {
      if (needed > bytes.length) throw new IOException("Probe limit exceeded");
      while (size < needed && !eof) {
        int n = input.read(bytes, size, needed - size);
        if (n < 0) eof = true;
        else if (n == 0) {
          int b = input.read();
          if (b < 0) eof = true;
          else bytes[size++] = (byte) b;
        } else size += n;
      }
      return size >= needed;
    }

    private boolean starts(String value) {
      return at(value, 0);
    }

    private boolean starts(byte[] value) {
      if (size < value.length) return false;
      for (int i = 0; i < value.length; i++) if (bytes[i] != value[i]) return false;
      return true;
    }

    private boolean at(String value, int offset) {
      if (size < offset + value.length()) return false;
      for (int i = 0; i < value.length(); i++) if (u(offset + i) != value.charAt(i)) return false;
      return true;
    }

    private void replay() throws IOException {
      input.unread(bytes, 0, size);
    }
  }
}
