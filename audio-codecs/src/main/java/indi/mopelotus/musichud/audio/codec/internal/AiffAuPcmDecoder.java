package indi.mopelotus.musichud.audio.codec.internal;

import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.*;
import java.util.Objects;

/** JDK-only AIFF/AIFC/AU PCM reader. Owns input; single reader, concurrent close. */
public final class AiffAuPcmDecoder implements PcmDecoder {
  private static final long MAX_HEADER_BYTES = 16L * 1024 * 1024;

  private enum Kind {
    INTEGER,
    UNSIGNED8,
    FLOAT32,
    FLOAT64,
    ULAW,
    ALAW
  }

  private final InputStream input;
  private final PcmFormat format;
  private final Kind kind;
  private final boolean littleEndian;
  private final int sourceBytes, sourceFrameBytes;
  private final long expectedFrames;
  private final byte[] buffer;
  private volatile long position;
  private volatile boolean closed;
  private IOException failure;

  /** Header parsing fails closed and releases the source exactly once. */
  public AiffAuPcmDecoder(InputStream source) throws IOException {
    input = Objects.requireNonNull(source);
    Header h;
    try {
      h = parseHeader();
    } catch (Exception e) {
      try {
        close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      if (e instanceof IOException io) throw io;
      throw new IOException("Invalid PCM container", e);
    }
    format = h.format;
    kind = h.kind;
    littleEndian = h.littleEndian;
    sourceBytes = h.sourceBytes;
    sourceFrameBytes = Math.multiplyExact(sourceBytes, format.channels());
    expectedFrames = h.frames;
    buffer = new byte[8192 * sourceFrameBytes];
  }

  private record Header(
      PcmFormat format, Kind kind, int sourceBytes, boolean littleEndian, long frames) {}

  private static int id(String s) {
    return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
  }

  private int u8() throws IOException {
    int n = input.read();
    if (n < 0) throw new EOFException("Truncated PCM container");
    return n;
  }

  private int u16() throws IOException {
    return (u8() << 8) | u8();
  }

  private long u32() throws IOException {
    return ((long) u16() << 16) | u16();
  }

  private void skipExact(long bytes) throws IOException {
    if (bytes == 0) return;
    byte[] scratch = new byte[8192];
    while (bytes > 0) {
      if (closed) throw new IOException("Decoder closed");
      int n = input.read(scratch, 0, (int) Math.min(bytes, scratch.length));
      if (n < 0) throw new EOFException("Truncated container chunk");
      if (n == 0) {
        u8();
        bytes--;
      } else bytes -= n;
    }
  }

  private static PcmFormat pcm(int rate, int channels, Kind kind, int bytes) throws IOException {
    if (rate < 1 || rate > 768000 || channels < 1 || channels > 8)
      throw new IOException("Unsupported PCM dimensions");
    PcmFormat.Encoding encoding =
        switch (kind) {
          case ULAW, ALAW -> PcmFormat.Encoding.S16LE;
          case FLOAT32, FLOAT64 -> PcmFormat.Encoding.F32LE;
          default ->
              switch (bytes) {
                case 1 -> PcmFormat.Encoding.S8;
                case 2 -> PcmFormat.Encoding.S16LE;
                case 3 -> PcmFormat.Encoding.S24LE;
                case 4 -> PcmFormat.Encoding.S32LE;
                default -> throw new IOException("Unsupported integer width");
              };
        };
    PcmFormat.Layout layout =
        channels == 1
            ? PcmFormat.Layout.MONO
            : channels == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.UNSPECIFIED;
    return new PcmFormat(rate, channels, encoding, layout);
  }

  /**
   * Exact integer conversion; rejects nonfinite, denormal, negative, fractional and oversized
   * rates.
   */
  private int extendedRate() throws IOException {
    int exponent = u16();
    long mantissa = (u32() << 32) | u32();
    int power = (exponent & 0x7fff) - 16383;
    if ((exponent & 0x8000) != 0 || power < 0 || power > 19 || mantissa >= 0)
      throw new IOException("Invalid AIFF extended sample rate");
    int shift = 63 - power;
    long fractionMask = (1L << shift) - 1;
    if ((mantissa & fractionMask) != 0)
      throw new IOException("Fractional AIFF sample rate cannot use integer PCM contract");
    long rate = mantissa >>> shift;
    if (rate < 1 || rate > 768000) throw new IOException("Unsupported sample rate");
    return (int) rate;
  }

  private Header parseHeader() throws IOException {
    long magic = u32();
    if (magic == Integer.toUnsignedLong(id(".snd"))) {
      long offset = u32(), bytes = u32(), code = u32(), rate = u32(), channels = u32();
      if (offset < 24 || offset > MAX_HEADER_BYTES) throw new IOException("Invalid AU data offset");
      Kind k;
      int width;
      switch ((int) code) {
        case 1:
          k = Kind.ULAW;
          width = 1;
          break;
        case 2:
          k = Kind.INTEGER;
          width = 1;
          break;
        case 3:
          k = Kind.INTEGER;
          width = 2;
          break;
        case 4:
          k = Kind.INTEGER;
          width = 3;
          break;
        case 5:
          k = Kind.INTEGER;
          width = 4;
          break;
        case 6:
          k = Kind.FLOAT32;
          width = 4;
          break;
        case 7:
          k = Kind.FLOAT64;
          width = 8;
          break;
        case 27:
          k = Kind.ALAW;
          width = 1;
          break;
        default:
          throw new IOException("Unsupported AU encoding " + code);
      }
      if (rate > Integer.MAX_VALUE || channels > Integer.MAX_VALUE)
        throw new IOException("Invalid AU dimensions");
      PcmFormat f = pcm((int) rate, (int) channels, k, width);
      int frame = width * f.channels();
      if (bytes != 0xffffffffL && bytes % frame != 0)
        throw new IOException("AU data is not frame aligned");
      skipExact(offset - 24);
      return new Header(f, k, width, false, bytes == 0xffffffffL ? -1 : bytes / frame);
    }
    if (magic != Integer.toUnsignedLong(id("FORM")))
      throw new IOException("Not an AIFF/AU container");
    long formBytes = u32(), type = u32();
    if (type != Integer.toUnsignedLong(id("AIFF")) && type != Integer.toUnsignedLong(id("AIFC")))
      throw new IOException("Unsupported FORM type");
    boolean compressed = type == Integer.toUnsignedLong(id("AIFC"));
    if (formBytes < 4) throw new IOException("Invalid FORM length");
    long remaining = formBytes - 4, scanned = 12;
    Header comm = null;
    int chunks = 0;
    while (remaining > 0) {
      if (remaining < 8 || ++chunks > 4096)
        throw new IOException("Invalid or excessive AIFF chunks");
      long chunk = u32(), length = u32();
      remaining -= 8;
      scanned += 8;
      long padded = length + (length & 1);
      if (padded > remaining) throw new IOException("AIFF chunk exceeds FORM length");
      if (chunk == Integer.toUnsignedLong(id("COMM"))) {
        if (comm != null || length < (compressed ? 22 : 18))
          throw new IOException("Invalid AIFF COMM");
        int channels = u16();
        long frames = u32();
        int bits = u16(), rate = extendedRate();
        long consumed = 18;
        String compression = "NONE";
        if (compressed) {
          compression = "" + (char) u8() + (char) u8() + (char) u8() + (char) u8();
          consumed += 4;
        }
        Kind k = Kind.INTEGER;
        boolean le = false;
        int width = (bits + 7) / 8;
        switch (compression) {
          case "NONE", "twos" -> {
            if (bits < 1 || bits > 32) throw new IOException("Invalid AIFF integer width");
          }
          case "sowt" -> {
            if (bits < 1 || bits > 32) throw new IOException("Invalid AIFC integer width");
            le = true;
          }
          case "in24" -> {
            if (bits != 24) throw new IOException("AIFC in24 width mismatch");
          }
          case "in32" -> {
            if (bits != 32) throw new IOException("AIFC in32 width mismatch");
          }
          case "raw " -> {
            if (bits != 8) throw new IOException("AIFC raw width mismatch");
            k = Kind.UNSIGNED8;
            width = 1;
          }
          case "fl32", "FL32" -> {
            if (bits != 32) throw new IOException("AIFC float width mismatch");
            k = Kind.FLOAT32;
            width = 4;
          }
          case "fl64", "FL64" -> {
            if (bits != 64) throw new IOException("AIFC double width mismatch");
            k = Kind.FLOAT64;
            width = 8;
          }
          case "ulaw", "ULAW" -> {
            if (bits != 8 && bits != 16) throw new IOException("AIFC law width mismatch");
            k = Kind.ULAW;
            width = 1;
          }
          case "alaw", "ALAW" -> {
            if (bits != 8 && bits != 16) throw new IOException("AIFC law width mismatch");
            k = Kind.ALAW;
            width = 1;
          }
          default -> throw new IOException("Unsupported AIFC compression " + compression);
        }
        PcmFormat f = pcm(rate, channels, k, width);
        comm = new Header(f, k, width, le, frames);
        if (compressed) {
          if (length - consumed < 1) throw new IOException("Missing AIFC compression-name string");
          int nameLength = u8();
          consumed++;
          if (nameLength > length - consumed)
            throw new IOException("Truncated AIFC compression name");
        }
        if (scanned + padded > MAX_HEADER_BYTES) throw new IOException("AIFF header exceeds bound");
        skipExact(padded - consumed);
      } else if (chunk == Integer.toUnsignedLong(id("SSND"))) {
        if (comm == null) throw new IOException("Streaming AIFF requires COMM before SSND");
        if (length < 8) throw new IOException("Invalid AIFF SSND");
        long offset = u32();
        u32();
        if (offset > length - 8 || scanned + 8 + offset > MAX_HEADER_BYTES)
          throw new IOException("Invalid AIFF SSND offset");
        long payload = length - 8 - offset,
            frame = (long) comm.sourceBytes * comm.format.channels(),
            needed = Math.multiplyExact(comm.frames, frame);
        if (payload < needed) throw new IOException("AIFF sound data length/frame mismatch");
        skipExact(offset);
        return comm;
      } else {
        if (scanned + padded > MAX_HEADER_BYTES) throw new IOException("AIFF header exceeds bound");
        skipExact(padded);
      }
      remaining -= padded;
      scanned += padded;
    }
    throw new IOException("Missing AIFF sound data");
  }

  @Override
  public PcmFormat format() {
    return format;
  }

  @Override
  public long framePosition() {
    return position;
  }

  private int readInput(byte[] b, int n) throws IOException {
    int at = 0;
    while (at < n) {
      if (closed) throw new IOException("Decoder closed");
      int r = input.read(b, at, n - at);
      if (r < 0) break;
      if (r == 0) {
        int value = input.read();
        if (value < 0) break;
        b[at++] = (byte) value;
      } else at += r;
    }
    return at;
  }

  private static int muLaw(int v) {
    v = (~v) & 255;
    int magnitude = ((v & 15) << 3) + 132;
    magnitude <<= (v >>> 4) & 7;
    return (v & 128) != 0 ? 132 - magnitude : magnitude - 132;
  }

  private static int aLaw(int v) {
    v ^= 0x55;
    int magnitude = (v & 15) << 4;
    int exponent = (v >>> 4) & 7;
    magnitude += exponent == 0 ? 8 : 264;
    if (exponent > 1) magnitude <<= exponent - 1;
    return (v & 128) != 0 ? magnitude : -magnitude;
  }

  @Override
  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    if (closed) throw new IOException("Decoder closed");
    if (failure != null) throw failure;
    Objects.requireNonNull(target);
    long requested = (long) maxFrames * format.frameBytes();
    if (maxFrames < 0 || requested > Integer.MAX_VALUE)
      throw new IndexOutOfBoundsException("Invalid frame count");
    Objects.checkFromIndexSize(offset, (int) requested, target.length);
    if (maxFrames == 0) return 0;
    if (expectedFrames >= 0 && position == expectedFrames) return -1;
    int count = Math.min(maxFrames, 8192);
    if (expectedFrames >= 0) count = (int) Math.min(count, expectedFrames - position);
    try {
      int bytes = readInput(buffer, count * sourceFrameBytes);
      if (closed) throw new IOException("Decoder closed");
      if (bytes % sourceFrameBytes != 0) throw new EOFException("Truncated PCM frame");
      int frames = bytes / sourceFrameBytes;
      if (expectedFrames >= 0 && frames < count)
        throw new EOFException("Truncated declared PCM data");
      if (frames == 0) return -1;
      synchronized (this) {
        if (closed) throw new IOException("Decoder closed");
        int out = offset, samples = frames * format.channels();
        for (int sample = 0; sample < samples; sample++) {
          int at = sample * sourceBytes;
          if (kind == Kind.ULAW || kind == Kind.ALAW) {
            int value = kind == Kind.ULAW ? muLaw(buffer[at] & 255) : aLaw(buffer[at] & 255);
            target[out++] = (byte) value;
            target[out++] = (byte) (value >>> 8);
          } else if (kind == Kind.FLOAT64) {
            long bits = 0;
            for (int i = 0; i < 8; i++) bits = (bits << 8) | (buffer[at + i] & 255L);
            float value = (float) Double.longBitsToDouble(bits);
            int raw = Float.floatToRawIntBits(value);
            for (int i = 0; i < 4; i++) target[out++] = (byte) (raw >>> (8 * i));
          } else if (kind == Kind.UNSIGNED8) target[out++] = (byte) (buffer[at] ^ 0x80);
          else if (littleEndian || sourceBytes == 1) {
            System.arraycopy(buffer, at, target, out, sourceBytes);
            out += sourceBytes;
          } else for (int i = sourceBytes - 1; i >= 0; i--) target[out++] = buffer[at + i];
        }
        if (closed) throw new IOException("Decoder closed");
        position += frames;
        return frames;
      }
    } catch (IOException e) {
      failure = e;
      try {
        close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    input.close();
  }
}
