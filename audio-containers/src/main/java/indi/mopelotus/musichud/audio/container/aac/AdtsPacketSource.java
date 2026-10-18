package indi.mopelotus.musichud.audio.container.aac;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.util.*;

/** Bounded AAC ADTS framing; header semantics follow Lavaplayer's Apache-2.0 AdtsStreamReader. */
public class AdtsPacketSource implements PacketSource {
  private static final int[] RATES = {
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
  };

  /** A timestamp anchors the first ADTS frame commencing at or after byteOffset. */
  protected record Anchor(
      long byteOffset, long identity, Time time, boolean discontinuity, long timelineEpoch) {}

  protected interface Feed extends Closeable {
    int read() throws IOException;

    default int read(byte[] b, int o, int n) throws IOException {
      int v = read();
      if (v < 0) return -1;
      b[o] = (byte) v;
      int i = 1;
      for (; i < n; i++) {
        v = read();
        if (v < 0) break;
        b[o + i] = (byte) v;
      }
      return i;
    }

    long position();

    Anchor anchor(long frameOffset) throws IOException;
  }

  private final Feed feed;
  private final Track track;
  private Packet pending;
  private long anchorIdentity = Long.MIN_VALUE;
  private long parsedEpoch, pendingEpoch, returnedEpoch;
  private Time nextTime;
  private Format previousFormat;
  private volatile boolean closed;

  public AdtsPacketSource(InputStream input) throws IOException {
    this(new RawFeed(input));
  }

  protected AdtsPacketSource(Feed feed) throws IOException {
    this.feed = Objects.requireNonNull(feed);
    try {
      pending = parse();
      pendingEpoch = parsedEpoch;
      if (pending == null) throw new EOFException("Empty ADTS input");
      track = new Track(pending.format(), null, List.of(), Time.ZERO, Time.ZERO);
    } catch (Throwable e) {
      try {
        feed.close();
      } catch (Throwable close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  public Track track() {
    return track;
  }

  public Packet next() throws IOException {
    check();
    if (pending != null) {
      Packet p = pending;
      pending = null;
      returnedEpoch = pendingEpoch;
      return p;
    }
    Packet p = parse();
    if (p != null) returnedEpoch = parsedEpoch;
    return p;
  }

  protected final long returnedTimelineEpoch() {
    return returnedEpoch;
  }

  private void check() throws IOException {
    if (closed) throw new IOException("ADTS source closed");
  }

  private int required() throws IOException {
    check();
    int b = feed.read();
    check();
    if (b < 0) throw new EOFException("Truncated ADTS frame");
    return b;
  }

  private void skipMetadata(int size) throws IOException {
    byte[] buffer = new byte[Math.min(size, 8192)];
    while (size > 0) {
      check();
      int n = feed.read(buffer, 0, Math.min(buffer.length, size));
      check();
      if (n < 0) throw new EOFException("Truncated ADTS ID3");
      if (n == 0) {
        required();
        n = 1;
      }
      size -= n;
    }
  }

  private Packet parse() throws IOException {
    check();
    long offset;
    int first;
    int metadataBytes = 0;
    while (true) {
      offset = feed.position();
      first = feed.read();
      check();
      if (first < 0) return null;
      if (first != 'I') break;
      byte[] tag = new byte[10];
      tag[0] = (byte) first;
      for (int i = 1; i < 10; i++) tag[i] = (byte) required();
      if (tag[1] != 'D' || tag[2] != '3' || tag[3] < 2 || tag[3] > 4)
        throw new IOException("Invalid ID3 before ADTS");
      int size = 0;
      for (int i = 6; i < 10; i++) {
        if ((tag[i] & 128) != 0) throw new IOException("ID3 synchsafe size");
        size = (size << 7) | (tag[i] & 127);
      }
      if (size > 16777216) throw new IOException("ADTS ID3 exceeds bound");
      if (tag[3] == 4 && (tag[5] & 16) != 0) size += 10;
      metadataBytes = Math.addExact(metadataBytes, size + 10);
      if (metadataBytes > 16777216) throw new IOException("ADTS metadata prefix exceeds bound");
      skipMetadata(size);
    }
    byte[] h = new byte[7];
    h[0] = (byte) first;
    for (int i = 1; i < 7; i++) h[i] = (byte) required();
    if ((h[0] & 255) != 255 || (h[1] & 0xf6) != 0xf0)
      throw new IOException("Invalid ADTS sync/layer");
    int index = (h[2] >>> 2) & 15;
    if (index >= RATES.length) throw new IOException("Reserved ADTS sample frequency");
    int channels = ((h[2] & 1) << 2) | ((h[3] >>> 6) & 3);
    if (channels == 0) throw new IOException("ADTS PCE channel configuration unsupported");
    int blocks = h[6] & 3;
    if (blocks != 0) throw new IOException("ADTS multiple raw_data_blocks unsupported");
    int profile = ((h[2] >>> 6) & 3) + 1, header = (h[1] & 1) != 0 ? 7 : 9;
    int size = ((h[3] & 3) << 11) | ((h[4] & 255) << 3) | ((h[5] >>> 5) & 7);
    if (size <= header) throw new IOException("ADTS length does not contain payload");
    if (header == 9) {
      required();
      required();
    }
    byte[] raw = new byte[size - header];
    for (int i = 0; i < raw.length; i++) raw[i] = (byte) required();
    Anchor a = feed.anchor(offset);
    if (a != null) parsedEpoch = a.timelineEpoch();
    boolean discontinuity = false;
    if (a != null && a.identity() != anchorIdentity) {
      anchorIdentity = a.identity();
      if (a.time() != null || a.discontinuity()) nextTime = a.time();
      discontinuity = a.discontinuity();
    }
    int asc = (profile << 11) | (index << 7) | (channels << 3);
    Format format = new Format("mp4a", new byte[] {(byte) (asc >>> 8), (byte) asc});
    if (previousFormat != null && !previousFormat.equals(format)) discontinuity = true;
    previousFormat = format;
    Time duration = new Time(1024, RATES[index]);
    Time pts = nextTime;
    if (nextTime != null) nextTime = add(nextTime, duration);
    return new Packet(
        format, pts, pts, duration, Time.ZERO, Time.ZERO, discontinuity, ByteBuffer.wrap(raw));
  }

  static Time add(Time a, Time b) throws IOException {
    BigInteger an = BigInteger.valueOf(a.ticks()),
        ad = BigInteger.valueOf(a.timescale()),
        bn = BigInteger.valueOf(b.ticks()),
        bd = BigInteger.valueOf(b.timescale());
    BigInteger n = an.multiply(bd).add(bn.multiply(ad)), d = ad.multiply(bd), g = n.gcd(d);
    try {
      return new Time(n.divide(g).longValueExact(), d.divide(g).longValueExact());
    } catch (ArithmeticException e) {
      throw new IOException("ADTS timestamp exceeds exact clock", e);
    }
  }

  public Time seekMedia(Time target) throws IOException {
    throw new IOException("ADTS stream is not seekable");
  }

  public void close() throws IOException {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    feed.close();
  }

  private static final class RawFeed implements Feed {
    private final InputStream input;
    private long offset;

    RawFeed(InputStream input) {
      this.input = new BufferedInputStream(Objects.requireNonNull(input), 8192);
    }

    public int read() throws IOException {
      int v = input.read();
      if (v >= 0) offset++;
      return v;
    }

    public int read(byte[] b, int o, int n) throws IOException {
      int r = input.read(b, o, n);
      if (r > 0) offset += r;
      return r;
    }

    public long position() {
      return offset;
    }

    public Anchor anchor(long position) {
      return new Anchor(0, 0, Time.ZERO, false, 0);
    }

    public void close() throws IOException {
      input.close();
    }
  }
}
