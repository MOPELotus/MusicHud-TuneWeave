package indi.mopelotus.musichud.audio.codec.internal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Mp3Headers {
  public record Header(
      String version,
      int sampleRate,
      int channels,
      int bitrate,
      int frameBytes,
      int samplesPerFrame,
      boolean crc) {}

  public record Metadata(
      Header header,
      long firstFrameOffset,
      String vbrKind,
      long declaredFrames,
      long declaredBytes,
      int encoderDelay,
      int encoderPadding) {}

  public record FramePoint(long byteOffset, long encodedSample, int frameBytes) {}

  static Header parse(byte[] b, int p) {
    int x =
        ((b[p] & 255) << 24)
            | ((b[p + 1] & 255) << 16)
            | ((b[p + 2] & 255) << 8)
            | (b[p + 3] & 255);
    int v = (x >>> 19) & 3, layer = (x >>> 17) & 3, bi = (x >>> 12) & 15, si = (x >>> 10) & 3;
    if ((x & 0xffe00000) != 0xffe00000
        || v == 1
        || layer != 1
        || bi == 0
        || bi == 15
        || si == 3
        || (x & 3) == 2) return null;
    int sr = new int[] {44100, 48000, 32000}[si] / (v == 3 ? 1 : v == 2 ? 2 : 4);
    int br =
        (v == 3
                    ? new int[] {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
                    : new int[] {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160})
                [bi]
            * 1000;
    return new Header(
        v == 3 ? "1" : v == 2 ? "2" : "2.5",
        sr,
        ((x >>> 6) & 3) == 3 ? 1 : 2,
        br,
        (v == 3 ? 144 : 72) * br / sr + ((x >>> 9) & 1),
        v == 3 ? 1152 : 576,
        ((x >>> 16) & 1) == 0);
  }

  static long skipTags(PushbackInputStream in) throws IOException {
    long skipped = 0;
    while (true) {
      byte[] h = in.readNBytes(10);
      if (h.length < 3 || h[0] != 'I' || h[1] != 'D' || h[2] != '3') {
        in.unread(h);
        return skipped;
      }
      if (h.length != 10) throw new EOFException("Truncated ID3 header");
      if ((h[3] & 255) < 2 || (h[3] & 255) > 4) throw new IOException("Unsupported ID3 version");
      int size = 0;
      for (int i = 6; i < 10; i++) {
        if (h[i] < 0) throw new IOException("Invalid ID3 synchsafe size");
        size = (size << 7) | (h[i] & 127);
      }
      long tail = size + ((h[3] == 4 && (h[5] & 16) != 0) ? 10L : 0L);
      in.skipNBytes(tail);
      skipped += 10 + tail;
    }
  }

  static long u32(byte[] b, int p) {
    return ((long) (b[p] & 255) << 24)
        | ((long) (b[p + 1] & 255) << 16)
        | ((long) (b[p + 2] & 255) << 8)
        | (b[p + 3] & 255);
  }

  static String tag(byte[] b, int p) {
    return p + 4 <= b.length ? new String(b, p, 4, StandardCharsets.US_ASCII) : "";
  }

  static Metadata inspect(byte[] b, Header h, long offset) {
    int p =
        4
            + (h.crc ? 2 : 0)
            + (h.version.equals("1") ? (h.channels == 1 ? 17 : 32) : (h.channels == 1 ? 9 : 17));
    String kind = tag(b, p);
    long frames = -1, bytes = -1;
    int delay = -1, padding = -1;
    if (kind.equals("Xing") || kind.equals("Info")) {
      if (p + 8 <= b.length) {
        long flags = u32(b, p + 4);
        p += 8;
        if ((flags & 1) != 0 && p + 4 <= b.length) {
          frames = u32(b, p);
          p += 4;
        }
        if ((flags & 2) != 0 && p + 4 <= b.length) {
          bytes = u32(b, p);
          p += 4;
        }
        if ((flags & 4) != 0) p += 100;
        if ((flags & 8) != 0) p += 4;
        if (p + 24 <= b.length && (tag(b, p).equals("LAME") || tag(b, p).equals("Lavc"))) {
          delay = ((b[p + 21] & 255) << 4) | ((b[p + 22] & 255) >>> 4);
          padding = ((b[p + 22] & 15) << 8) | (b[p + 23] & 255);
        }
      }
    } else if (tag(b, 36).equals("VBRI") && b.length >= 54) {
      kind = "VBRI";
      bytes = u32(b, 46);
      frames = u32(b, 50);
    } else kind = "none";
    return new Metadata(h, offset, kind, frames, bytes, delay, padding);
  }

  /**
   * Exact frame offsets, no PCM decoding. Caller owns scan stream. A seek must pre-roll the bit
   * reservoir and synthesis history.
   */
  public static List<FramePoint> scanIndex(InputStream input) throws IOException {
    PushbackInputStream in = new PushbackInputStream(new FillingStream(input), 10);
    long pos = skipTags(in), sample = 0;
    List<FramePoint> out = new ArrayList<>();
    byte[] h = in.readNBytes(4);
    while (h.length == 4) {
      Header parsed = parse(h, 0);
      if (parsed == null) throw new IOException("Invalid MPEG frame at " + pos);
      if (in.readNBytes(parsed.frameBytes - 4).length != parsed.frameBytes - 4)
        throw new EOFException("Truncated MPEG frame at " + pos);
      out.add(new FramePoint(pos, sample, parsed.frameBytes));
      pos += parsed.frameBytes;
      sample += parsed.samplesPerFrame;
      h = in.readNBytes(4);
      if (h.length == 4 && tag(h, 0).startsWith("TAG")) break;
    }
    if (h.length > 0 && h.length < 4) throw new EOFException("Truncated final header");
    return List.copyOf(out);
  }

  /** Normalizes legal short reads. Bound zero-progress sources; does not invent EOF. */
  private static final class FillingStream extends FilterInputStream {
    FillingStream(InputStream in) {
      super(in);
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      int n = 0, zeros = 0;
      while (n < len) {
        int r = in.read(b, off + n, len - n);
        if (r < 0) return n == 0 ? -1 : n;
        if (r == 0) {
          if (++zeros > 32) throw new IOException("Input made no progress");
        } else {
          n += r;
          zeros = 0;
        }
      }
      return n;
    }
  }
}
