package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.DecodedPacket;
import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Translates explicitly quantized container clocks to a continuous exact packet/sample lattice. Use
 * exactly one overload on a stream path; never map both compressed and decoded packets. A
 * configuration discontinuity does not reset time. The owner explicitly resets true epochs.
 */
public final class QuantizedPcmClock {
  private final Fraction quantum;
  private Fraction expected;
  private final boolean matroska;
  private Fraction lastReported;
  private WeakReference<PacketSource.Packet> lastPacket;
  private Boolean decodedStage;

  /** PTS must already include the demuxer's single subtraction of CodecDelay. */
  public QuantizedPcmClock(PacketSource.Time timestampQuantum) {
    this(timestampQuantum, false);
  }

  private QuantizedPcmClock(PacketSource.Time timestampQuantum, boolean matroska) {
    this.matroska = matroska;
    quantum = Fraction.of(Objects.requireNonNull(timestampQuantum));
    if (quantum.n.signum() <= 0)
      throw new IllegalArgumentException("Timestamp quantum must be positive");
  }

  /** Matroska alone has nanosecond padding and tick-quantized block durations to normalize. */
  public static QuantizedPcmClock matroska(PacketSource.Time timestampQuantum) {
    return new QuantizedPcmClock(timestampQuantum, true);
  }

  /**
   * TS/ADTS path with an exact compressed packet duration. Does not guess a decoder sample rate,
   * touch discard fields, or reset on configuration-only discontinuity flags.
   */
  public PacketSource.Packet map(PacketSource.Packet packet) throws IOException {
    Objects.requireNonNull(packet);
    if (matroska) throw new IOException("Matroska clock needs decoded sample counts");
    if (packet.presentationTime() == null
        || packet.duration() == null
        || packet.duration().ticks() <= 0) {
      throw new IOException(
          "Compressed quantized clock needs known PTS and positive exact duration");
    }
    try {
      requireStage(packet, false);
      Fraction start = alignedStart(Fraction.of(packet.presentationTime()), 0, false);
      var mapped =
          new PacketSource.Packet(
              packet.format(),
              shiftedDecode(packet, start),
              start.time(),
              packet.duration(),
              packet.discardStart(),
              packet.discardEnd(),
              packet.discontinuity(),
              packet.encoded());
      expected = start.plus(Fraction.of(packet.duration()));
      remember(packet);
      return mapped;
    } catch (ArithmeticException | IllegalArgumentException error) {
      throw new IOException("Invalid quantized packet clock", error);
    }
  }

  /** A seek/new logical stream requires a fresh anchor; this does not retain codec state. */
  public void reset() {
    expected = null;
    lastReported = null;
    lastPacket = null;
    decodedStage = null;
  }

  /**
   * Returns a derived presentation-clock packet; the original packet/PCM snapshot is unchanged. No
   * PCM is trimmed and CodecDelay/header pre-skip is never applied here.
   */
  public DecodedPacket map(DecodedPacket decoded) throws IOException {
    Objects.requireNonNull(decoded);
    try {
      var p = decoded.source();
      requireStage(p, true);
      int rate = decoded.format().sampleRate();
      Fraction raw = p.presentationTime() == null ? null : Fraction.of(p.presentationTime());
      Fraction start = alignedStart(raw, rate, matroska);
      Fraction full =
          new Fraction(BigInteger.valueOf(decoded.rawFrames()), BigInteger.valueOf(rate));
      Fraction duration = full;
      PacketSource.Time mappedDuration = p.duration();
      if (p.duration() != null) {
        Fraction declared = Fraction.of(p.duration());
        if (declared.compareTo(full) < 0) duration = declared;
        if (matroska) {
          duration =
              declared.minus(full).abs().compareTo(quantum) <= 0 || declared.compareTo(full) >= 0
                  ? full
                  : declared.nearestSample(rate);
        }
      }
      if (matroska) mappedDuration = duration.time();
      Fraction head =
          matroska
              ? Fraction.of(p.discardStart()).nearestSample(rate)
              : Fraction.of(p.discardStart());
      Fraction tail =
          matroska ? Fraction.of(p.discardEnd()).nearestSample(rate) : Fraction.of(p.discardEnd());
      if (head.plus(tail).compareTo(duration) > 0)
        throw new IOException("Matroska padding exceeds decoded extent");
      if (decoded.rawFrames() != 0) expected = start.plus(duration);
      var aligned =
          new PacketSource.Packet(
              p.format(),
              shiftedDecode(p, start),
              start.time(),
              mappedDuration,
              head.time(),
              tail.time(),
              p.discontinuity(),
              p.encoded());
      var result = new DecodedPacket(aligned, decoded.format(), decoded.pcm());
      remember(p);
      return result;
    } catch (ArithmeticException | IllegalArgumentException failure) {
      throw new IOException("Invalid Matroska PCM clock", failure);
    }
  }

  private void requireStage(PacketSource.Packet packet, boolean decoded) throws IOException {
    if (decodedStage != null && decodedStage != decoded)
      throw new IOException("Do not map at both compressed and decoded stages");
    decodedStage = decoded;
    if (lastPacket != null && lastPacket.get() == packet)
      throw new IOException("Packet was mapped twice");
    if (packet.presentationTime() != null
        && lastReported != null
        && Fraction.of(packet.presentationTime()).compareTo(lastReported) < 0) {
      throw new IOException("Reported audio packet timestamps moved backwards");
    }
  }

  private void remember(PacketSource.Packet packet) {
    lastPacket = new WeakReference<>(packet); // Does not retain a previous large encoded packet.
    if (packet.presentationTime() != null) lastReported = Fraction.of(packet.presentationTime());
  }

  private PacketSource.Time shiftedDecode(PacketSource.Packet packet, Fraction start) {
    if (packet.decodeTime() == null || packet.presentationTime() == null)
      return packet.decodeTime();
    return Fraction.of(packet.decodeTime())
        .plus(start.minus(Fraction.of(packet.presentationTime())))
        .time();
  }

  private Fraction alignedStart(Fraction raw, int rate, boolean inferMissingLace)
      throws IOException {
    if (raw == null) {
      if (!inferMissingLace || expected == null)
        throw new IOException("Missing first quantized packet timestamp");
      return expected;
    }
    if (expected == null) return rate == 0 ? raw : raw.nearestSample(rate);
    Fraction delta = raw.minus(expected);
    if (delta.abs().compareTo(quantum) <= 0) return expected;
    if (delta.n.signum() < 0)
      throw new IOException("Packet timestamp overlap exceeds its declared quantum");
    return rate == 0 ? raw : raw.nearestSample(rate);
  }

  private record Fraction(BigInteger n, BigInteger d) implements Comparable<Fraction> {
    Fraction {
      if (d.signum() <= 0) throw new IllegalArgumentException();
      BigInteger gcd = n.gcd(d);
      n = n.divide(gcd);
      d = d.divide(gcd);
    }

    static Fraction of(PacketSource.Time t) {
      return new Fraction(BigInteger.valueOf(t.ticks()), BigInteger.valueOf(t.timescale()));
    }

    Fraction plus(Fraction t) {
      return new Fraction(n.multiply(t.d).add(t.n.multiply(d)), d.multiply(t.d));
    }

    Fraction minus(Fraction t) {
      return plus(new Fraction(t.n.negate(), t.d));
    }

    Fraction abs() {
      return new Fraction(n.abs(), d);
    }

    Fraction nearestSample(int rate) {
      BigInteger scaled = n.multiply(BigInteger.valueOf(rate));
      BigInteger[] qr = scaled.divideAndRemainder(d);
      BigInteger frames = qr[0];
      if (qr[1].abs().shiftLeft(1).compareTo(d) >= 0)
        frames = frames.add(BigInteger.valueOf(scaled.signum()));
      return new Fraction(frames, BigInteger.valueOf(rate));
    }

    PacketSource.Time time() {
      return new PacketSource.Time(n.longValueExact(), d.longValueExact());
    }

    @Override
    public int compareTo(Fraction t) {
      return n.multiply(t.d).compareTo(t.n.multiply(d));
    }
  }
}
