package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.Mp4PacketSource;
import indi.mopelotus.musichud.audio.container.input.HlsPlaybackInput;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

/**
 * HLS fragmented MP4 packets. Initialization edits are global metadata, never per-segment trims.
 */
public final class HlsFmp4PacketSource implements PacketSource {
  private final Combined combined;
  private final NormalizedPacketSource normalized;
  private final Track track;

  public HlsFmp4PacketSource(HlsPlaybackInput owned, HlsPlaybackInput.SegmentData first)
      throws IOException {
    combined = new Combined(owned, first);
    try {
      normalized = new NormalizedPacketSource(combined, () -> combined.returnedEpoch);
      track = globalTrack(combined.original, combined.origin);
    } catch (Throwable failure) {
      try {
        combined.close();
      } catch (Throwable close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  public Track track() {
    return track;
  }

  public Packet next() throws IOException {
    return normalized.next();
  }

  public Time seekMedia(Time target) throws IOException {
    throw new IOException("HLS fMP4 requires reopen for seek");
  }

  public void close() throws IOException {
    normalized.close();
  }

  private static boolean oneRate(Edit e) {
    return e.rate().numerator() == e.rate().denominator();
  }

  private static boolean openOffset(List<Edit> edits) {
    return edits.size() == 1
        && edits.getFirst().presentationDuration().ticks() == 0
        && edits.getFirst().mediaStart() != null
        && oneRate(edits.getFirst());
  }

  private static boolean identity(List<Edit> edits) {
    return edits.isEmpty() || openOffset(edits) && edits.getFirst().mediaStart().ticks() == 0;
  }

  private static Track globalTrack(Track original, Time origin) throws IOException {
    List<Edit> edits = original.edits();
    Time delay = original.codecDelay();
    if (identity(edits)) edits = List.of();
    else if (openOffset(edits)) {
      Time offset = subtract(edits.getFirst().mediaStart(), origin);
      if (offset.ticks() < 0) offset = Time.ZERO;
      delay = offset.compareValue(delay) > 0 ? offset : delay;
      edits = List.of();
    } else {
      if (origin.ticks() != 0)
        throw new IOException(
            "Finite fMP4 edits with a nonzero first-fragment origin require window mapping");
      for (Edit edit : edits) {
        if (!oneRate(edit)) throw new IOException("Unsupported HLS edit rate");
        if (edit.presentationDuration().ticks() == 0)
          throw new IOException(
              "Multiple open-ended HLS edits require explicit presentation mapping");
      }
    }
    return new Track(original.initialFormat(), null, edits, delay, original.seekPreRoll());
  }

  private static Time subtract(Time a, Time b) throws IOException {
    BigInteger ad = BigInteger.valueOf(a.timescale()),
        bd = BigInteger.valueOf(b.timescale()),
        n =
            BigInteger.valueOf(a.ticks())
                .multiply(bd)
                .subtract(BigInteger.valueOf(b.ticks()).multiply(ad)),
        d = ad.multiply(bd),
        g = n.gcd(d);
    try {
      return new Time(n.divide(g).longValueExact(), d.divide(g).longValueExact());
    } catch (ArithmeticException e) {
      throw new IOException("HLS edit offset exceeds exact clock", e);
    }
  }

  private static final class Combined implements PacketSource {
    private final HlsPlaybackInput input;
    private volatile boolean closed;
    private final Object publication = new Object();
    private Mp4PacketSource current;
    private HlsPlaybackInput.SegmentData segment;
    private Packet pending;
    private Track original;
    private Time origin;
    private Format previousFormat;
    private boolean firstInSegment;
    private long returnedEpoch, firstEpoch;

    Combined(HlsPlaybackInput owned, HlsPlaybackInput.SegmentData first) throws IOException {
      input = Objects.requireNonNull(owned);
      try {
        open(first == null ? input.next() : first);
        if (current == null || (pending = current.next()) == null)
          throw new EOFException("Empty HLS fMP4 input");
        original = current.track();
        origin = pending.presentationTime();
        if (origin == null) throw new IOException("Missing first fMP4 PTS");
        firstEpoch = segment.discontinuitySequence();
      } catch (Throwable failure) {
        try {
          close();
        } catch (Throwable close) {
          failure.addSuppressed(close);
        }
        throw failure;
      }
    }

    private void check() throws IOException {
      if (closed) throw new IOException("HLS fMP4 closed");
    }

    private void open(HlsPlaybackInput.SegmentData next) throws IOException {
      check();
      if (next == null) {
        current = null;
        return;
      }
      byte[] init = next.initialization();
      if (init == null) throw new IOException("HLS fMP4 requires initialization MAP");
      byte[] media = next.media();
      var opened = new Mp4PacketSource(new Memory(init, media), new Memory(init, media));
      if (original != null) {
        if (!original.edits().equals(opened.track().edits())
            && (!identity(original.edits()) || !identity(opened.track().edits()))) {
          opened.close();
          throw new IOException(
              "Changed nonidentity HLS initialization edits require period mapping");
        }
        if (next.discontinuitySequence() != firstEpoch && !identity(original.edits())) {
          opened.close();
          throw new IOException("Edited fMP4 HLS epochs require per-period priming mapping");
        }
      }
      synchronized (publication) {
        if (closed) {
          opened.close();
          throw new IOException("HLS fMP4 closed during parse");
        }
        current = opened;
        segment = next;
        firstInSegment = true;
      }
    }

    public Track track() {
      return new Track(original.initialFormat(), null, List.of(), Time.ZERO, Time.ZERO);
    }

    public Packet next() throws IOException {
      check();
      for (; ; ) {
        if (current == null) return null;
        Packet p = pending;
        if (p != null) pending = null;
        else p = current.next();
        if (p == null) {
          current.close();
          current = null;
          open(input.next());
          continue;
        }
        boolean reset =
            p.discontinuity()
                || firstInSegment && segment.segment().discontinuity()
                || previousFormat != null && !previousFormat.equals(p.format());
        firstInSegment = false;
        previousFormat = p.format();
        returnedEpoch = segment.discontinuitySequence();
        check();
        return new Packet(
            p.format(),
            p.decodeTime(),
            p.presentationTime(),
            p.duration(),
            p.discardStart(),
            p.discardEnd(),
            reset,
            p.encoded());
      }
    }

    public Time seekMedia(Time target) throws IOException {
      throw new IOException("HLS fMP4 requires reopen");
    }

    public void close() throws IOException {
      Mp4PacketSource old;
      synchronized (publication) {
        closed = true;
        old = current;
      }
      input.close();
      if (old != null) old.close();
    }
  }

  /** Two immutable views share bounded segment bytes while keeping independent seek positions. */
  private static final class Memory implements SeekableByteChannel {
    private final byte[] init, media;
    private final long length;
    private long position;
    private boolean open = true;

    Memory(byte[] init, byte[] media) {
      this.init = init;
      this.media = media;
      length = (long) init.length + media.length;
    }

    private void check() throws ClosedChannelException {
      if (!open) throw new ClosedChannelException();
    }

    public int read(ByteBuffer out) throws IOException {
      check();
      if (!out.hasRemaining()) return 0;
      if (position == length) return -1;
      int wanted = (int) Math.min(out.remaining(), length - position), remaining = wanted;
      while (remaining > 0) {
        byte[] part = position < init.length ? init : media;
        int at = (int) (position < init.length ? position : position - init.length),
            n = Math.min(remaining, part.length - at);
        out.put(part, at, n);
        position += n;
        remaining -= n;
      }
      return wanted;
    }

    public int write(ByteBuffer in) throws IOException {
      throw new NonWritableChannelException();
    }

    public long position() throws IOException {
      check();
      return position;
    }

    public SeekableByteChannel position(long value) throws IOException {
      check();
      if (value < 0 || value > length) throw new IOException("MP4 memory seek outside resource");
      position = value;
      return this;
    }

    public long size() throws IOException {
      check();
      return length;
    }

    public SeekableByteChannel truncate(long value) throws IOException {
      throw new NonWritableChannelException();
    }

    public boolean isOpen() {
      return open;
    }

    public void close() {
      open = false;
    }
  }
}
