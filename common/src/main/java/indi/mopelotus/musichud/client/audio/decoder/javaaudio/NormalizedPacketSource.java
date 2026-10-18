package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Forward-only elementary packet clock. Starts at zero and joins explicitly identified epochs. */
public final class NormalizedPacketSource implements PacketSource {
  private final PacketSource source;
  private final LongSupplier epoch;
  private final Track track;
  private final QuantizedPcmClock quantized;
  private final AtomicBoolean closed = new AtomicBoolean();
  private Rational offset = Rational.ZERO, previousEnd = Rational.ZERO;
  private long currentEpoch;
  private boolean started;

  public NormalizedPacketSource(PacketSource owned) throws IOException {
    this(owned, () -> 0);
  }

  /** epoch must describe the packet just returned by next, never parser read-ahead state. */
  public NormalizedPacketSource(PacketSource owned, LongSupplier epoch) throws IOException {
    this(owned, epoch, null);
  }

  /** A nonnull quantum enables only this container's explicit timestamp resolution. */
  public NormalizedPacketSource(PacketSource owned, LongSupplier epoch, Time quantum)
      throws IOException {
    source = Objects.requireNonNull(owned);
    try {
      this.epoch = Objects.requireNonNull(epoch);
      quantized = quantum == null ? null : new QuantizedPcmClock(quantum);
      Track original = source.track();
      if (!original.edits().isEmpty())
        throw new IOException("Elementary origin mapper cannot apply container edits");
      track =
          new Track(
              original.initialFormat(),
              null,
              List.of(),
              original.codecDelay(),
              original.seekPreRoll());
    } catch (Throwable failure) {
      try {
        source.close();
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
    check();
    Packet packet = source.next();
    check();
    if (packet == null) return null;
    long returnedEpoch = epoch.getAsLong();
    boolean newEpoch = !started || returnedEpoch != currentEpoch;
    if (quantized != null) {
      if (newEpoch) quantized.reset();
      packet = quantized.map(packet);
    }
    Time pts = packet.presentationTime(), duration = packet.duration();
    if (pts == null || duration == null)
      throw new IOException("Elementary normalization requires known PTS and duration");
    if (newEpoch) {
      offset = previousEnd.subtract(Rational.of(pts));
      currentEpoch = returnedEpoch;
      started = true;
    }
    Rational mapped = Rational.of(pts).add(offset);
    Time dts =
        packet.decodeTime() == null ? null : Rational.of(packet.decodeTime()).add(offset).time();
    previousEnd = mapped.add(Rational.of(duration));
    check();
    return new Packet(
        packet.format(),
        dts,
        mapped.time(),
        duration,
        packet.discardStart(),
        packet.discardEnd(),
        packet.discontinuity(),
        packet.encoded());
  }

  private void check() throws IOException {
    if (closed.get()) throw new IOException("Normalized packet source closed");
  }

  private record Rational(BigInteger numerator, BigInteger denominator) {
    static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    Rational {
      BigInteger gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
    }

    static Rational of(Time t) {
      return new Rational(BigInteger.valueOf(t.ticks()), BigInteger.valueOf(t.timescale()));
    }

    Rational add(Rational other) {
      return new Rational(
          numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }

    Rational subtract(Rational other) {
      return new Rational(
          numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }

    Time time() throws IOException {
      try {
        return new Time(numerator.longValueExact(), denominator.longValueExact());
      } catch (ArithmeticException overflow) {
        throw new IOException(
            "Normalized packet clock exceeds exact long representation", overflow);
      }
    }
  }

  public Time seekMedia(Time target) throws IOException {
    throw new IOException("Elementary packet source requires reopen for seek");
  }

  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) source.close();
  }
}
