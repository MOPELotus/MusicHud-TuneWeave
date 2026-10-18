package indi.mopelotus.musichud.audio.codec;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An owned, synchronous source of compressed audio packets and exact container timing.
 *
 * <p>Decoders return raw media PCM. The presentation layer maps edits and applies priming and
 * discard metadata once. A container delay and the equivalent codec-header pre-skip describe the
 * same delay; they must not be added together. Unknown time is {@code null}, never an invented
 * zero. Sources preserve timestamp gaps and configuration changes instead of joining unrelated
 * timelines.
 *
 * <p>Only one thread calls {@link #next()} or {@link #seekMedia(Time)} at a time. {@link #close()}
 * may be called by another thread and must release the owned input and unblock a blocked read.
 * Construction failure also releases an input whose ownership has been transferred to the source.
 */
public interface PacketSource extends Closeable {
  int MAX_PACKET_BYTES = 16 * 1024 * 1024;
  int MAX_CODEC_PRIVATE_BYTES = 1024 * 1024;

  /** Exact signed time. Units remain ticks/timescale; no floating-point conversion is implied. */
  record Time(long ticks, long timescale) {
    public static final Time ZERO = new Time(0, 1);

    public Time {
      if (timescale <= 0) throw new IllegalArgumentException("Timescale must be positive");
    }

    /** Compares mathematical values without multiplying overflowing longs. */
    public int compareValue(Time other) {
      Objects.requireNonNull(other, "other");
      return BigInteger.valueOf(ticks)
          .multiply(BigInteger.valueOf(other.timescale))
          .compareTo(BigInteger.valueOf(other.ticks).multiply(BigInteger.valueOf(timescale)));
    }
  }

  /** Exact playback rate from an edit list, including a zero/dwell or negative rate if present. */
  record Rate(long numerator, long denominator) {
    public static final Rate ONE = new Rate(1, 1);

    public Rate {
      if (denominator <= 0) throw new IllegalArgumentException("Rate denominator must be positive");
    }
  }

  /** Immutable codec identity and original codec configuration bytes. */
  record Format(String codec, byte[] codecPrivate) {
    public Format {
      Objects.requireNonNull(codec, "codec");
      if (codec.isBlank()) throw new IllegalArgumentException("Codec must not be blank");
      Objects.requireNonNull(codecPrivate, "codecPrivate");
      if (codecPrivate.length > MAX_CODEC_PRIVATE_BYTES)
        throw new IllegalArgumentException("Codec configuration exceeds bound");
      codecPrivate = codecPrivate.clone();
    }

    @Override
    public byte[] codecPrivate() {
      return codecPrivate.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Format format
          && codec.equals(format.codec)
          && Arrays.equals(codecPrivate, format.codecPrivate);
    }

    @Override
    public int hashCode() {
      return 31 * codec.hashCode() + Arrays.hashCode(codecPrivate);
    }

    @Override
    public String toString() {
      return "Format[codec=" + codec + ", codecPrivateBytes=" + codecPrivate.length + "]";
    }
  }

  /**
   * Ordered presentation edit. Durations use the movie's exact clock, mediaStart the media clock. A
   * null mediaStart means an empty presentation interval, not an unknown start. Edit positions are
   * cumulative from presentation zero. Unsupported rates must be rejected by the mapper.
   */
  record Edit(Time mediaStart, Time presentationDuration, Rate rate) {
    public Edit {
      nonnegative(
          Objects.requireNonNull(presentationDuration, "presentationDuration"), "Edit duration");
      Objects.requireNonNull(rate, "rate");
    }
  }

  /**
   * Immutable initial stream metadata. mediaDuration is the raw media timeline endpoint if known,
   * including declared timestamp gaps; it is not a promise of the final edited playback duration.
   * codecDelay and seekPreRoll are exact nonnegative durations; absent values use Time.ZERO.
   */
  record Track(
      Format initialFormat,
      Time mediaDuration,
      List<Edit> edits,
      Time codecDelay,
      Time seekPreRoll) {
    public Track {
      Objects.requireNonNull(initialFormat, "initialFormat");
      if (mediaDuration != null) nonnegative(mediaDuration, "Media duration");
      edits = List.copyOf(Objects.requireNonNull(edits, "edits"));
      nonnegative(Objects.requireNonNull(codecDelay, "codecDelay"), "Codec delay");
      nonnegative(Objects.requireNonNull(seekPreRoll, "seekPreRoll"), "Seek preroll");
    }
  }

  /**
   * One complete codec packet. Its format may change; callers must rebuild or explicitly reject an
   * incompatible decoder configuration. DTS/PTS/duration are nullable only when unknown.
   * discardStart/discardEnd are nonnegative durations applying to this packet's raw decoded PCM. A
   * positive gap remains in timestamps. discontinuity requires decoder/timeline reset even if the
   * next numerical timestamp happens to be adjacent. encoded owns an immutable byte snapshot.
   */
  record Packet(
      Format format,
      Time decodeTime,
      Time presentationTime,
      Time duration,
      Time discardStart,
      Time discardEnd,
      boolean discontinuity,
      ByteBuffer encoded) {
    public Packet {
      Objects.requireNonNull(format, "format");
      if (duration != null) nonnegative(duration, "Packet duration");
      nonnegative(Objects.requireNonNull(discardStart, "discardStart"), "Start discard");
      nonnegative(Objects.requireNonNull(discardEnd, "discardEnd"), "End discard");
      ByteBuffer input = Objects.requireNonNull(encoded, "encoded").duplicate();
      if (input.remaining() > MAX_PACKET_BYTES)
        throw new IllegalArgumentException("Encoded packet exceeds bound");
      ByteBuffer copy = ByteBuffer.allocate(input.remaining());
      copy.put(input).flip();
      encoded = copy.asReadOnlyBuffer();
    }

    /** Independent position/limit on every access; the underlying bytes cannot be changed. */
    @Override
    public ByteBuffer encoded() {
      return encoded.asReadOnlyBuffer();
    }
  }

  Track track();

  /** Returns the next complete packet or null at clean EOF; truncated input throws IOException. */
  Packet next() throws IOException;

  /**
   * Repositions in the raw media clock, retaining required codec preroll. Returns the first
   * available packet timestamp, or null if it is unknown/EOF. A source without seeking throws
   * IOException. The caller maps presentation edits before calling and discards preroll PCM.
   */
  Time seekMedia(Time target) throws IOException;

  @Override
  void close() throws IOException;

  private static void nonnegative(Time time, String name) {
    if (time.ticks < 0) throw new IllegalArgumentException(name + " must not be negative");
  }
}
