package indi.mopelotus.musichud.audio.codec.internal.wma;

import com.sedmelluq.discord.lavaplayer.natives.wma.WmaDecoder;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded forward-only ASF/WMA v1/v2 F32LE adapter, derived from pinned devoxin/lavaplayer
 * WmaFileLoader/WmaTrackProvider. No transport dependency. One reader only. Concurrent close is
 * supported and closes the input once; an input that supports close-to-cancel can interrupt its own
 * blocked reads. Packet resync, encryption, WMA Pro/lossless, and indexed seek are unsupported.
 */
public final class AsfWmaInputStream extends InputStream {
  public record Format(
      int version,
      int channels,
      int sampleRate,
      int bitRate,
      int blockAlign,
      int flags2,
      int streamId,
      long durationMillis,
      long prerollMillis,
      int packetSize,
      long totalPackets,
      byte[] extraData) {
    public Format {
      extraData = extraData.clone();
    }

    @Override
    public byte[] extraData() {
      return extraData.clone();
    }
  }

  private static final int MAX_HEADER = 32 * 1024 * 1024, MAX_PACKET = 1024 * 1024;
  private static final byte[] HEADER = hex("3026b2758e66cf11a6d900aa0062ce6c"),
      FILE = hex("a1dcab8c47a9cf118ee400c00c205365"),
      STREAM = hex("9107dcb7b7a9cf118ee600c00c205365"),
      AUDIO = hex("409e69f84d5bcf11a8fd00805f5c442b"),
      AUDIO_SPREAD = hex("50cdc3bf8f61cf118bb200aa00b4e220"),
      DATA = hex("3626b2758e66cf11a6d900aa0062ce6c");
  private final InputStream input;
  private final Format format;
  private final WmaDecoder decoder;
  private final byte[] packet;
  private final byte[] object;
  private long packetsRead, outputFrames, lastTimestampMillis = -1;
  private int skip, objectFill, objectNumber = -1, objectSize;
  private byte[] pcm = new byte[0];
  private int pcmPosition;
  private boolean drained, failed;
  private final AtomicBoolean closed = new AtomicBoolean();

  public AsfWmaInputStream(InputStream input) throws IOException {
    this.input = Objects.requireNonNull(input);
    try {
      format = readHeaders(input);
      decoder =
          new WmaDecoder(
              format.sampleRate,
              format.channels,
              format.bitRate,
              format.blockAlign,
              format.version,
              format.flags2);
      skip = 2 * decoder.frameLen;
      packet = new byte[format.packetSize];
      object = new byte[format.blockAlign];
    } catch (IOException | RuntimeException | Error failure) {
      closed.set(true);
      try {
        input.close();
      } catch (Throwable closeFailure) {
        if (closeFailure != failure) failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public Format format() {
    return format;
  }

  public long outputFrames() {
    return outputFrames;
  }

  public long lastPacketTimestampMillis() {
    return lastTimestampMillis;
  }

  public static boolean matches(byte[] b) {
    return b.length >= 16 && Arrays.equals(b, 0, 16, HEADER, 0, 16);
  }

  private static Format readHeaders(InputStream in) throws IOException {
    byte[] start = exact(in, 30);
    if (!matches(start)) throw new IOException("Not ASF");
    long hs = i64(start, 16);
    if (hs < 30 || hs > MAX_HEADER) throw new IOException("ASF header size out of range");
    byte[] h = new byte[(int) hs];
    System.arraycopy(start, 0, h, 0, 30);
    readFully(in, h, 30, h.length - 30);
    long count = u32(h, 24);
    if (count > 100000) throw new IOException("ASF object count limit");
    int rate = 0, ch = 0, br = 0, align = 0, flags = 0, ver = 0, id = 0, ps = 0;
    long dur = 0, preroll = 0;
    byte[] extra = new byte[0];
    int at = 30;
    for (long n = 0; n < count; n++) {
      require(h, at, 24);
      long size = i64(h, at + 16);
      if (size < 24 || size > h.length - at) throw new IOException("ASF object size");
      int end = at + (int) size;
      if (eq(h, at, FILE)) {
        if (size < 104) throw new IOException("Truncated file properties");
        dur = i64(h, at + 64) / 10000;
        preroll = i64(h, at + 80);
        long min = u32(h, at + 92), max = u32(h, at + 96);
        if (min != max || max < 16 || max > MAX_PACKET)
          throw new IOException("Variable/oversized ASF packets unsupported");
        ps = (int) max;
      } else if (eq(h, at, STREAM)) {
        if (size < 78) throw new IOException("Truncated stream properties");
        if (eq(h, at + 24, AUDIO)) {
          if (rate != 0) throw new IOException("Multiple audio streams unsupported");
          long ts = u32(h, at + 64), ec = u32(h, at + 68);
          int streamFlags = u16(h, at + 72);
          if ((streamFlags & 0x8000) != 0) throw new IOException("Encrypted ASF unsupported");
          if (ts < 18 || ts > size - 78 || ec > size - 78 - ts)
            throw new IOException("ASF stream data lengths");
          // Span-one audio-spread correction is an identity transform. Other schemes
          // require a descrambler; rejecting them avoids returning plausible but wrong PCM.
          if (ec != 0) {
            int correction = at + 78 + (int) ts;
            if (!eq(h, at + 40, AUDIO_SPREAD) || ec < 8 || (h[correction] & 255) != 1)
              throw new IOException("ASF audio descrambling unsupported");
          }
          int p = at + 78;
          int tag = u16(h, p);
          if (tag != 0x160 && tag != 0x161)
            throw new IOException("Unsupported WMA codec tag " + tag);
          ver = tag == 0x160 ? 1 : 2;
          ch = u16(h, p + 2);
          long sr = u32(h, p + 4), avg = u32(h, p + 8);
          align = u16(h, p + 12);
          int cb = u16(h, p + 16);
          if (cb > ts - 18) throw new IOException("ASF extradata length");
          if (ch < 1
              || ch > 2
              || sr < 8000
              || sr > 50000
              || avg < 1
              || avg > Integer.MAX_VALUE / 8
              || align < 1
              || align > 32768) throw new IOException("Unsupported WMA audio configuration");
          rate = (int) sr;
          br = (int) avg * 8;
          id = streamFlags & 127;
          if (id == 0) throw new IOException("Invalid ASF stream number");
          extra = Arrays.copyOfRange(h, p + 18, p + 18 + cb);
          int fo = ver == 2 ? 4 : 2;
          if (cb < fo + 2) throw new IOException("Missing WMA flags");
          flags = u16(extra, fo);
        }
      }
      at = end;
    }
    if (at != h.length || rate == 0 || ps == 0) throw new IOException("Incomplete ASF header");
    // Valid ASF files may have intervening top-level objects (padding/index).
    byte[] dh = exact(in, 24);
    long skipped = 0;
    while (!eq(dh, 0, DATA)) {
      long size = i64(dh, 16);
      if (size < 24 || size > MAX_HEADER || skipped + size > MAX_HEADER)
        throw new IOException("ASF data object missing");
      skipFully(in, size - 24);
      skipped += size;
      dh = exact(in, 24);
    }
    long ds = i64(dh, 16);
    if (ds < 50) throw new IOException("Invalid ASF data object size");
    byte[] rest = exact(in, 26);
    long packets = i64(rest, 16);
    if (packets < 0 || packets > (ds - 50) / ps)
      throw new IOException("ASF data packet count/length mismatch");
    if (preroll < 0 || dur < preroll) throw new IOException("Invalid ASF duration/preroll");
    return new Format(
        ver, ch, rate, br, align, flags, id, dur - preroll, preroll, ps, packets, extra);
  }

  @Override
  public int read() throws IOException {
    byte[] b = new byte[1];
    return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    Objects.checkFromIndexSize(off, len, b.length);
    if (closed.get()) throw new IOException("Closed");
    if (failed) throw new IOException("Decoder previously failed");
    if (len == 0) return 0;
    try {
      while (pcmPosition == pcm.length) {
        if (closed.get()) throw new IOException("Closed");
        if (drained) return -1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (packetsRead < format.totalPackets) {
          readFully(input, packet, 0, packet.length);
          packetsRead++;
          parsePacket(out);
        } else {
          if (objectFill != 0) throw new IOException("Truncated ASF media object");
          emit(out, decoder.drainOverlap());
          drained = true;
        }
        pcm = out.toByteArray();
        pcmPosition = 0;
      }
      if (closed.get()) throw new IOException("Closed");
      int n = Math.min(len, pcm.length - pcmPosition);
      System.arraycopy(pcm, pcmPosition, b, off, n);
      pcmPosition += n;
      return n;
    } catch (RuntimeException e) {
      failed = true;
      throw new IOException("Malformed ASF/WMA data", e);
    } catch (IOException e) {
      failed = true;
      throw e;
    }
  }

  private void parsePacket(ByteArrayOutputStream out) throws IOException {
    Cursor c = new Cursor(packet, packet.length);
    int lf = c.u8();
    if ((lf & 128) != 0) {
      if ((lf & 0x60) != 0) throw new IOException("Unsupported ASF error correction");
      c.skip(lf & 15);
      lf = c.u8();
    }
    int pf = c.u8();
    int pl = c.length((lf >> 5) & 3);
    c.length((lf >> 1) & 3);
    int pad = c.length((lf >> 3) & 3);
    long send = c.u32();
    c.skip(2);
    lastTimestampMillis = send - format.prerollMillis;
    int total = ((lf >> 5) & 3) != 0 ? pl : packet.length;
    if (total < 0 || total > packet.length || pad < 0 || pad > total - c.p)
      throw new IOException("ASF packet/padding size");
    c.limit = total - pad;
    boolean multi = (lf & 1) != 0;
    int num = 1, lenType = 0;
    if (multi) {
      int v = c.u8();
      num = v & 63;
      lenType = v >> 6;
      if (num == 0 || lenType == 0) throw new IOException("Invalid ASF payload count/length type");
    }
    for (int i = 0; i < num; i++) {
      int streamType = (pf >> 6) & 3;
      if (streamType != 1) throw new IOException("Unsupported ASF stream number size");
      int sid = c.u8() & 127;
      int number = c.length((pf >> 4) & 3),
          offset = c.length((pf >> 2) & 3),
          rep = c.length(pf & 3);
      if (rep < 0 || rep > c.limit - c.p) throw new IOException("ASF replicated data size");
      int size = -1;
      long timestamp = -1;
      if (rep >= 8) {
        size = c.i32();
        timestamp = c.u32();
        c.skip(rep - 8);
      } else if (rep == 1) {
        timestamp = offset;
        c.skip(1);
      } else if (rep != 0) throw new IOException("Unsupported replicated data");
      int n = multi ? c.length(lenType) : c.limit - c.p;
      if (n < 0 || n > c.limit - c.p) throw new IOException("ASF payload size");
      int end = c.p + n;
      if (sid == format.streamId) {
        if (rep == 1) {
          while (c.p < end) {
            int sub = c.u8();
            if (sub < 1 || sub > end - c.p) throw new IOException("Compressed ASF payload size");
            decode(out, packet, c.p, sub);
            c.skip(sub);
          }
        } else {
          if (timestamp >= 0) lastTimestampMillis = timestamp - format.prerollMillis;
          assemble(out, number, offset, size, c.p, n);
          c.p = end;
        }
      } else c.p = end;
    }
    if (c.p != c.limit) throw new IOException("Unparsed ASF packet data");
  }

  private void assemble(ByteArrayOutputStream out, int number, int offset, int size, int p, int n)
      throws IOException {
    if (size < 0) size = format.blockAlign;
    if (size != format.blockAlign || offset < 0 || n > size - offset)
      throw new IOException("ASF WMA object size/fragment range");
    if (offset == 0) {
      if (objectFill != 0) throw new IOException("Unfinished ASF media object");
      objectNumber = number;
      objectSize = size;
    }
    if (number != objectNumber || offset != objectFill)
      throw new IOException("Discontinuous ASF media object");
    if (offset == 0 && n == size) {
      decode(out, packet, p, n);
      objectNumber = -1;
      return;
    }
    System.arraycopy(packet, p, object, offset, n);
    objectFill += n;
    if (objectFill == objectSize) {
      decode(out, object, 0, objectSize);
      objectFill = 0;
      objectNumber = -1;
    }
  }

  private void decode(ByteArrayOutputStream out, byte[] b, int p, int n) throws IOException {
    if (n != format.blockAlign) throw new IOException("Unexpected WMA block size");
    int frames = decoder.decode(b, p, n);
    if (frames < 0 || frames > 16) throw new IOException("WMA decoding failure");
    emit(out, frames * decoder.frameLen);
  }

  private void emit(ByteArrayOutputStream out, int frames) throws IOException {
    int from = Math.min(skip, frames);
    skip -= from;
    for (int i = from; i < frames; i++)
      for (int ch = 0; ch < format.channels; ch++) {
        float v = decoder.samples[ch][i];
        if (!Float.isFinite(v)) throw new IOException("Nonfinite WMA sample");
        int x = Float.floatToRawIntBits(v);
        out.write(x);
        out.write(x >> 8);
        out.write(x >> 16);
        out.write(x >> 24);
      }
    outputFrames += frames - from;
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      input.close();
    }
  }

  private static final class Cursor {
    final byte[] b;
    int p, limit;

    Cursor(byte[] b, int l) {
      this.b = b;
      limit = l;
    }

    void skip(int n) throws IOException {
      if (n < 0 || n > limit - p) throw new EOFException("ASF packet field");
      p += n;
    }

    int u8() throws IOException {
      if (p >= limit) throw new EOFException("ASF packet field");
      return b[p++] & 255;
    }

    int i32() throws IOException {
      int x = (int) u32();
      return x;
    }

    long u32() throws IOException {
      long x = 0;
      for (int i = 0; i < 4; i++) x |= (long) u8() << (8 * i);
      return x;
    }

    int length(int t) throws IOException {
      int n = t == 0 ? 0 : t == 1 ? 1 : t == 2 ? 2 : 4;
      long x = 0;
      for (int i = 0; i < n; i++) x |= (long) u8() << (i * 8);
      if (x > Integer.MAX_VALUE) throw new IOException("ASF field exceeds bound");
      return (int) x;
    }
  }

  private static byte[] exact(InputStream in, int n) throws IOException {
    byte[] b = new byte[n];
    readFully(in, b, 0, n);
    return b;
  }

  private static void readFully(InputStream in, byte[] b, int p, int n) throws IOException {
    while (n > 0) {
      int x = in.read(b, p, n);
      if (x < 0) throw new EOFException("Truncated ASF");
      if (x == 0) {
        int v = in.read();
        if (v < 0) throw new EOFException("Truncated ASF");
        b[p++] = (byte) v;
        n--;
      } else {
        p += x;
        n -= x;
      }
    }
  }

  private static void skipFully(InputStream in, long n) throws IOException {
    byte[] b = new byte[8192];
    while (n > 0) {
      int k = (int) Math.min(n, b.length);
      readFully(in, b, 0, k);
      n -= k;
    }
  }

  private static int u16(byte[] b, int p) throws IOException {
    require(b, p, 2);
    return (b[p] & 255) | ((b[p + 1] & 255) << 8);
  }

  private static long u32(byte[] b, int p) throws IOException {
    require(b, p, 4);
    return (b[p] & 255L)
        | ((b[p + 1] & 255L) << 8)
        | ((b[p + 2] & 255L) << 16)
        | ((b[p + 3] & 255L) << 24);
  }

  private static long i64(byte[] b, int p) throws IOException {
    require(b, p, 8);
    long v = 0;
    for (int i = 0; i < 8; i++) v |= (b[p + i] & 255L) << (i * 8);
    return v;
  }

  private static boolean eq(byte[] b, int p, byte[] g) {
    return p >= 0 && p <= b.length - 16 && Arrays.equals(b, p, p + 16, g, 0, 16);
  }

  private static void require(byte[] b, int p, int n) throws IOException {
    if (p < 0 || n < 0 || p > b.length - n) throw new EOFException("ASF header field");
  }

  private static byte[] hex(String x) {
    return HexFormat.of().parseHex(x);
  }
}
