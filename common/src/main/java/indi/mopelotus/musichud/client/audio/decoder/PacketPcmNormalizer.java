package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.DecodedPacket;
import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.IncrementalPcmResampler;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.PresentationPlan;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** One-packet bounded raw-media presentation normalizer. Edit-list mapping remains outside. */
public final class PacketPcmNormalizer implements AutoCloseable {
  public record Chunk(long targetFrameStart, ByteBuffer pcm, int frames) {
    public Chunk {
      pcm = pcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public ByteBuffer pcm() {
      return pcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
  }

  private final int targetRate;
  private final float[] zeros = new float[2048];
  private IncrementalPcmResampler resampler;
  private ByteBuffer packetPcm;
  private PacketPcmFold fold;
  private float[] input;
  private int inputOffset;
  private int sourceRate, lastSourceRate, sourceFrameBytes, minimumRate = Integer.MAX_VALUE;
  private long gapFrames;
  private Exact gapResidual = Exact.ZERO;
  private Exact timelineOffset = Exact.ZERO, previousEnd, previousDeclaredEnd, latticeOrigin;
  private int latticeRate;
  private long nextOutputFrame;
  private long resetBoundary;
  private boolean initialized, resetPending, inputFinished, finished, closed, downmixed;
  private boolean deferredDiscontinuity, deferredRestart;

  public PacketPcmNormalizer() {
    this(44100);
  }

  public PacketPcmNormalizer(int targetRate) {
    if (targetRate < 7350 || targetRate > 384000) throw new IllegalArgumentException("Target rate");
    this.targetRate = targetRate;
    resampler = new IncrementalPcmResampler(targetRate);
  }

  public PcmFormat format() {
    return new PcmFormat(
        targetRate,
        2,
        PcmFormat.Encoding.F32LE,
        PcmFormat.Layout.STEREO,
        PcmFormat.SpeakerLayout.fromWaveMask(3));
  }

  public boolean hasDownmixedChannels() {
    return downmixed;
  }

  public boolean isFinished() {
    return finished;
  }

  public boolean canAccept() {
    return !closed
        && !inputFinished
        && packetPcm == null
        && !resetPending
        && input == null
        && gapFrames == 0;
  }

  public boolean accept(DecodedPacket decoded) throws IOException {
    return accept(decoded, false);
  }

  /** False is backpressure: caller retains the packet and must pull existing output first. */
  public boolean accept(DecodedPacket decoded, boolean restartTimeline) throws IOException {
    try {
      return acceptPacket(decoded, restartTimeline);
    } catch (ArithmeticException | IllegalArgumentException invalid) {
      throw new IOException("Invalid packet presentation boundary", invalid);
    }
  }

  private boolean acceptPacket(DecodedPacket decoded, boolean restartTimeline) throws IOException {
    requireOpen();
    Objects.requireNonNull(decoded);
    if (!canAccept()) return false;
    PcmFormat f = decoded.format();
    if (f.sampleRate() < 7350 || f.sampleRate() > 384000)
      throw new IOException("Unsupported packet sample rate");
    PacketPcmFold nextFold = new PacketPcmFold(f);
    var packet = decoded.source();
    if (packet.presentationTime() == null) throw new IOException("Unknown packet PTS");
    Exact rawStart = Exact.of(packet.presentationTime());
    Exact origin =
        latticeOrigin == null || latticeRate != f.sampleRate() || restartTimeline || deferredRestart
            ? rawStart
            : latticeOrigin;
    Exact relativeStart = rawStart.minus(origin);
    long sourceStartFrame = relativeStart.ceilFrames(f.sampleRate());
    long from = PresentationPlan.framesCeil(packet.discardStart(), f.sampleRate());
    long declared =
        packet.duration() == null
            ? decoded.rawFrames()
            : Math.subtractExact(
                relativeStart.plus(Exact.of(packet.duration())).ceilFrames(f.sampleRate()),
                sourceStartFrame);
    long to =
        Math.min(decoded.rawFrames(), declared)
            - PresentationPlan.framesCeil(packet.discardEnd(), f.sampleRate());
    if (from < 0 || to < from || to > decoded.rawFrames())
      throw new IOException("Packet discard/duration exceeds PCM");
    Exact start =
        origin.plus(
            new Exact(
                BigInteger.valueOf(Math.addExact(sourceStartFrame, from)),
                BigInteger.valueOf(f.sampleRate())));
    int frames = Math.toIntExact(to - from);
    Exact declaredLength =
        new Exact(BigInteger.valueOf(decoded.rawFrames()), BigInteger.valueOf(f.sampleRate()));
    if (packet.duration() != null && Exact.of(packet.duration()).compareTo(declaredLength) < 0) {
      declaredLength = Exact.of(packet.duration());
    }
    Exact declaredStart = rawStart.plus(Exact.of(packet.discardStart()));
    Exact declaredEnd = rawStart.plus(declaredLength).minus(Exact.of(packet.discardEnd()));
    if (declaredStart.compareTo(declaredEnd) > 0)
      throw new IOException("Packet discard exceeds exact duration");
    if (frames == 0 && !initialized) {
      if (!restartTimeline && !deferredRestart) {
        Exact mappedStart = declaredStart.plus(timelineOffset);
        if (previousDeclaredEnd != null && mappedStart.compareTo(previousDeclaredEnd) < 0) {
          throw new IOException("Overlapping exact packet windows");
        }
        previousDeclaredEnd = declaredEnd.plus(timelineOffset);
      }
      latticeOrigin = origin;
      latticeRate = f.sampleRate();
      deferredDiscontinuity |= packet.discontinuity();
      deferredRestart |= restartTimeline;
      return true;
    }
    restartTimeline |= deferredRestart;
    boolean discontinuity = packet.discontinuity() || deferredDiscontinuity;
    deferredRestart = deferredDiscontinuity = false;
    if (restartTimeline && previousEnd != null) timelineOffset = previousEnd.minus(start);
    start = start.plus(timelineOffset);
    declaredStart = declaredStart.plus(timelineOffset);
    declaredEnd = declaredEnd.plus(timelineOffset);
    if (!restartTimeline
        && previousDeclaredEnd != null
        && declaredStart.compareTo(previousDeclaredEnd) < 0) {
      throw new IOException("Overlapping exact packet windows");
    }
    if (previousEnd != null && start.compareTo(previousEnd) < 0) {
      Exact overlap = previousEnd.minus(start);
      int rate = Math.min(lastSourceRate == 0 ? f.sampleRate() : lastSourceRate, f.sampleRate());
      if (overlap.compareTo(new Exact(BigInteger.ONE, BigInteger.valueOf(rate))) > 0) {
        throw new IOException("Overlapping packet PCM exceeds sample quantization");
      }
      start = previousEnd;
    }
    Exact end =
        start.plus(new Exact(BigInteger.valueOf(frames), BigInteger.valueOf(f.sampleRate())));
    long targetEnd = end.ceilFrames(targetRate);
    Math.subtractExact(targetEnd, initialized ? nextOutputFrame : start.ceilFrames(targetRate));
    if (previousEnd != null && start.compareTo(previousEnd) < 0)
      throw new IOException("Overlapping packet PTS");
    Exact gap = previousEnd == null ? Exact.ZERO : start.minus(previousEnd);
    if (!initialized) {
      nextOutputFrame = start.ceilFrames(targetRate);
      initialized = true;
    }
    if ((discontinuity || restartTimeline) && previousEnd != null) {
      resetPending = true;
      resetBoundary = previousEnd.ceilFrames(targetRate);
    }
    int gapRate = lastSourceRate == 0 || resetPending ? f.sampleRate() : lastSourceRate;
    Exact accruedGap = gapResidual.plus(gap);
    gapFrames = accruedGap.floorFrames(gapRate);
    gapResidual =
        accruedGap.minus(new Exact(BigInteger.valueOf(gapFrames), BigInteger.valueOf(gapRate)));
    // A reset's old residual is already represented by its boundary correction; don't count it
    // twice.
    if (resetPending) {
      gapFrames = gap.floorFrames(gapRate);
      gapResidual =
          gap.minus(new Exact(BigInteger.valueOf(gapFrames), BigInteger.valueOf(gapRate)));
    }
    sourceRate = f.sampleRate();
    sourceFrameBytes = f.frameBytes();
    minimumRate = Math.min(minimumRate, Math.min(sourceRate, gapRate));
    ByteBuffer pcm = decoded.pcm();
    pcm.position(Math.toIntExact(from * f.frameBytes()));
    pcm.limit(Math.toIntExact(to * f.frameBytes()));
    packetPcm = pcm.slice();
    fold = nextFold;
    downmixed |= frames > 0 && nextFold.hasDownmixedChannels();
    previousEnd = end;
    previousDeclaredEnd = declaredEnd;
    latticeOrigin = origin;
    latticeRate = f.sampleRate();
    return true;
  }

  /** Null means more input is needed, or finished=true. At most 4096 target frames per chunk. */
  public Chunk pull(int maxFrames) throws IOException {
    requireOpen();
    if (maxFrames < 1 || maxFrames > 4096) throw new IllegalArgumentException("Chunk bound");
    if (finished) return null;
    float[] output = new float[maxFrames * 2];
    for (int attempts = 0; attempts < 100000; attempts++) {
      if (resetPending) {
        var p = resampler.finish(output, 0, maxFrames);
        if (p.outputFramesProduced() > 0) return chunk(output, p.outputFramesProduced());
        if (!p.finished()) continue;
        int padded = completeBoundary(output, maxFrames, resetBoundary);
        if (padded > 0) return chunk(output, padded);
        resampler.close();
        resampler = new IncrementalPcmResampler(targetRate);
        resetPending = false;
        lastSourceRate = 0;
      }
      int available = resampler.drainAvailable(output, 0, maxFrames);
      if (available > 0) return chunk(output, available);
      if (input != null) {
        var p =
            resampler.process(
                input,
                inputOffset * 2,
                input.length / 2 - inputOffset,
                lastSourceRate,
                output,
                0,
                maxFrames);
        inputOffset += p.inputFramesConsumed();
        if (inputOffset == input.length / 2) {
          input = null;
          inputOffset = 0;
        }
        if (p.outputFramesProduced() > 0) return chunk(output, p.outputFramesProduced());
        continue;
      }
      if (gapFrames > 0) {
        int rate = lastSourceRate == 0 ? sourceRate : lastSourceRate;
        var p =
            resampler.process(
                zeros, 0, (int) Math.min(1024, gapFrames), rate, output, 0, maxFrames);
        gapFrames -= p.inputFramesConsumed();
        if (p.outputFramesProduced() > 0) return chunk(output, p.outputFramesProduced());
        continue;
      }
      if (packetPcm != null) {
        if (!packetPcm.hasRemaining()) {
          packetPcm = null;
          fold = null;
          continue;
        }
        int frames = Math.min(1024, packetPcm.remaining() / sourceFrameBytes);
        input = fold.convert(packetPcm, frames);
        inputOffset = 0;
        lastSourceRate = sourceRate;
        continue;
      }
      if (!inputFinished) return null;
      var p = resampler.finish(output, 0, maxFrames);
      if (p.outputFramesProduced() > 0) return chunk(output, p.outputFramesProduced());
      if (!p.finished()) continue;
      int padded =
          initialized ? completeBoundary(output, maxFrames, previousEnd.ceilFrames(targetRate)) : 0;
      if (padded > 0) return chunk(output, padded);
      finished = true;
      return null;
    }
    throw new IOException("Normalizer made no bounded progress");
  }

  public void finish() throws IOException {
    requireOpen();
    inputFinished = true;
  }

  private int completeBoundary(float[] output, int maxFrames, long targetEnd) throws IOException {
    long missing = targetEnd - nextOutputFrame;
    int bound = 2 + (targetRate + minimumRate - 1) / minimumRate;
    if (missing < 0 || missing > bound)
      throw new IOException("Unexpected exact-clock frame discrepancy " + missing);
    int count = (int) Math.min(missing, maxFrames);
    java.util.Arrays.fill(output, 0, count * 2, 0);
    return count;
  }

  private Chunk chunk(float[] values, int frames) {
    ByteBuffer bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < frames * 2; i++) bytes.putFloat(values[i]);
    bytes.flip();
    Chunk chunk = new Chunk(nextOutputFrame, bytes, frames);
    nextOutputFrame = Math.addExact(nextOutputFrame, frames);
    return chunk;
  }

  private void requireOpen() throws IOException {
    if (closed) throw new IOException("Normalizer closed");
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      resampler.close();
      packetPcm = null;
      input = null;
    }
  }

  private record Exact(BigInteger numerator, BigInteger denominator) implements Comparable<Exact> {
    private static final Exact ZERO = new Exact(BigInteger.ZERO, BigInteger.ONE);

    Exact {
      if (denominator.signum() <= 0) throw new IllegalArgumentException();
      BigInteger gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
    }

    static Exact of(PacketSource.Time t) {
      return new Exact(BigInteger.valueOf(t.ticks()), BigInteger.valueOf(t.timescale()));
    }

    Exact plus(Exact t) {
      return new Exact(
          numerator.multiply(t.denominator).add(t.numerator.multiply(denominator)),
          denominator.multiply(t.denominator));
    }

    Exact minus(Exact t) {
      return plus(new Exact(t.numerator.negate(), t.denominator));
    }

    long floorFrames(int rate) {
      BigInteger[] qr =
          numerator.multiply(BigInteger.valueOf(rate)).divideAndRemainder(denominator);
      return qr[0].subtract(qr[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }

    long ceilFrames(int rate) {
      BigInteger[] qr =
          numerator.multiply(BigInteger.valueOf(rate)).divideAndRemainder(denominator);
      return qr[0].add(qr[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }

    @Override
    public int compareTo(Exact t) {
      return numerator.multiply(t.denominator).compareTo(t.numerator.multiply(denominator));
    }
  }
}
