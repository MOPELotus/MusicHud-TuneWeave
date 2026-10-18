package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.math.BigInteger;
import java.util.*;

/** Exact edit mapping on a PCM frame grid; each boundary selects the first sample at/after it. */
public record PresentationPlan(List<Segment> segments, long totalFrames) {
  public record Segment(long mediaFrame, long frames) {
    public Segment {
      if (mediaFrame < -1 || frames < 0) throw new IllegalArgumentException();
    }

    public boolean silence() {
      return mediaFrame == -1;
    }
  }

  public PresentationPlan {
    segments = List.copyOf(segments);
  }

  public static PresentationPlan from(
      PacketSource.Track track, int sampleRate, boolean coreAlreadyTrimmed) {
    if (sampleRate <= 0) throw new IllegalArgumentException();
    var segments = new ArrayList<Segment>();
    if (coreAlreadyTrimmed) {
      if (!track.edits().isEmpty())
        throw new IllegalArgumentException("Trimmed core cannot also apply container edits");
      return new PresentationPlan(List.of(new Segment(0, Long.MAX_VALUE)), Long.MAX_VALUE);
    }
    if (track.edits().isEmpty()) {
      long first = framesCeil(track.codecDelay(), sampleRate);
      long last =
          track.mediaDuration() == null
              ? Long.MAX_VALUE
              : framesCeil(track.mediaDuration(), sampleRate);
      if (first > last) throw new IllegalArgumentException("Delay exceeds media duration");
      return new PresentationPlan(List.of(new Segment(first, last - first)), last - first);
    }
    BigInteger numerator = BigInteger.ZERO, denominator = BigInteger.ONE;
    long previous = 0;
    for (var edit : track.edits()) {
      if (edit.rate().numerator() != edit.rate().denominator())
        throw new IllegalArgumentException("Unsupported edit playback rate");
      var duration = edit.presentationDuration();
      BigInteger d = BigInteger.valueOf(duration.timescale());
      numerator =
          numerator.multiply(d).add(BigInteger.valueOf(duration.ticks()).multiply(denominator));
      denominator = denominator.multiply(d);
      BigInteger gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
      long end = ceil(numerator.multiply(BigInteger.valueOf(sampleRate)), denominator);
      long start = edit.mediaStart() == null ? -1 : framesCeil(edit.mediaStart(), sampleRate);
      if (edit.mediaStart() != null && edit.mediaStart().ticks() < 0)
        throw new IllegalArgumentException("Negative media edit");
      long count = end - previous;
      if (start >= 0 && track.mediaDuration() != null) {
        long mediaEnd = framesCeil(track.mediaDuration(), sampleRate);
        long requestedEnd = Math.addExact(start, count);
        if (requestedEnd > mediaEnd) {
          long rounding =
              ceil(BigInteger.valueOf(sampleRate), BigInteger.valueOf(duration.timescale()));
          long excess = requestedEnd - mediaEnd;
          if (start > mediaEnd || excess >= rounding)
            throw new IllegalArgumentException("Edit exceeds media extent");
          segments.add(new Segment(start, count - excess));
          segments.add(new Segment(-1, excess));
        } else segments.add(new Segment(start, count));
      } else segments.add(new Segment(start, count));
      previous = end;
    }
    return new PresentationPlan(segments, previous);
  }

  public static long durationFrames(PacketSource.Time start, PacketSource.Time duration, int rate) {
    BigInteger scale =
        BigInteger.valueOf(start.timescale()).multiply(BigInteger.valueOf(duration.timescale()));
    BigInteger ticks =
        BigInteger.valueOf(start.ticks())
            .multiply(BigInteger.valueOf(duration.timescale()))
            .add(
                BigInteger.valueOf(duration.ticks())
                    .multiply(BigInteger.valueOf(start.timescale())));
    return Math.subtractExact(
        ceil(ticks.multiply(BigInteger.valueOf(rate)), scale), framesCeil(start, rate));
  }

  public static long framesCeil(PacketSource.Time time, int rate) {
    return ceil(
        BigInteger.valueOf(time.ticks()).multiply(BigInteger.valueOf(rate)),
        BigInteger.valueOf(time.timescale()));
  }

  private static long ceil(BigInteger numerator, BigInteger denominator) {
    var qr = numerator.divideAndRemainder(denominator);
    return qr[0].add(qr[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
  }
}
