package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import com.laszlosystems.libresample4j.Resampler;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;

/** Fixed-rate stereo adapter around unchanged libresample4j; owns no stream or native resource. */
public final class IncrementalPcmResampler implements AutoCloseable {
  public record Progress(int inputFramesConsumed, int outputFramesProduced, boolean finished) {}

  private static final int BLOCK = 1024;
  private static final int MAX_ROUNDING_ALLOWANCE = 54;
  private final int targetRate;
  private final float[][] inputPlanes = new float[2][BLOCK];
  private final float[][] outputPlanes = new float[2][BLOCK];
  private final float[] pending = new float[(BLOCK + MAX_ROUNDING_ALLOWANCE) * 2];
  private int pendingFrames;
  private Resampler[] cores;
  private int sourceRate;
  private boolean flushing, coreDrained, finishing, finished, closed;
  private BigInteger clockNumerator = BigInteger.ZERO;
  private BigInteger clockDenominator = BigInteger.ONE;
  private long epochBudgetStart, epochGenerated, emitted;

  public IncrementalPcmResampler(int targetRate) {
    requireRate(targetRate);
    this.targetRate = targetRate;
  }

  public int targetRate() {
    return targetRate;
  }

  public long emittedFrames() {
    return emitted;
  }

  /** Offsets are interleaved sample indices; lengths/capacities are stereo frames. */
  public Progress process(
      float[] input,
      int inputOffset,
      int frames,
      int inputRate,
      float[] output,
      int outputOffset,
      int outputCapacityFrames)
      throws IOException {
    requireOpen();
    if (finishing) throw new IOException("Resampler is finishing");
    if (input == output) throw new IllegalArgumentException("In-place resampling is unsupported");
    bounds(input, inputOffset, frames);
    bounds(output, outputOffset, outputCapacityFrames);
    requireRate(inputRate);
    if (frames == 0 || outputCapacityFrames == 0) return new Progress(0, 0, false);
    if (sourceRate != 0 && (flushing || sourceRate != inputRate)) {
      flushing = true;
      int produced = flushEpoch(output, outputOffset, outputCapacityFrames);
      if (produced > 0) return new Progress(0, produced, false);
      if (!coreDrained) return new Progress(0, 0, false);
      clearEpoch();
    }
    if (sourceRate == 0) startEpoch(inputRate);
    int already = drain(output, outputOffset, outputCapacityFrames);
    if (already > 0) return new Progress(0, already, false);
    int accepted = Math.min(frames, BLOCK);
    for (int i = 0; i < accepted; i++)
      for (int channel = 0; channel < 2; channel++) {
        float value = input[inputOffset + i * 2 + channel];
        if (!Float.isFinite(value)) throw new IOException("Non-finite input PCM");
        inputPlanes[channel][i] = value;
      }
    int consumed;
    int generated;
    if (sourceRate == targetRate) {
      consumed = accepted;
      generated = accepted;
      for (int c = 0; c < 2; c++) System.arraycopy(inputPlanes[c], 0, outputPlanes[c], 0, accepted);
    } else {
      Resampler.Result first =
          cores[0].process(inputPlanes[0], 0, accepted, false, outputPlanes[0], 0, BLOCK);
      Resampler.Result second =
          cores[1].process(inputPlanes[1], 0, accepted, false, outputPlanes[1], 0, BLOCK);
      matching(first, second);
      consumed = first.inputSamplesConsumed;
      generated = first.outputSamplesGenerated;
    }
    addTime(consumed, sourceRate);
    append(generated);
    int produced = drain(output, outputOffset, outputCapacityFrames);
    return new Progress(consumed, produced, false);
  }

  /** Drains already generated PCM without accepting another source packet. */
  public int drainAvailable(float[] output, int offset, int capacityFrames) throws IOException {
    requireOpen();
    bounds(output, offset, capacityFrames);
    return drain(output, offset, capacityFrames);
  }

  /** Repeated calls drain the filter and exact clock budget. Once called, input cannot resume. */
  public Progress finish(float[] output, int outputOffset, int outputCapacityFrames)
      throws IOException {
    requireOpen();
    bounds(output, outputOffset, outputCapacityFrames);
    finishing = true;
    if (finished) return new Progress(0, 0, true);
    if (outputCapacityFrames == 0) return new Progress(0, 0, false);
    int produced = sourceRate == 0 ? 0 : flushEpoch(output, outputOffset, outputCapacityFrames);
    if ((sourceRate == 0 || coreDrained) && pendingFrames == 0 && emitted == budget())
      finished = true;
    return new Progress(0, produced, finished);
  }

  private int flushEpoch(float[] output, int offset, int capacity) throws IOException {
    int produced = drain(output, offset, capacity);
    if (produced > 0) return produced;
    if (!coreDrained) {
      if (cores == null) coreDrained = true;
      else {
        Resampler.Result first =
            cores[0].process(inputPlanes[0], 0, 0, true, outputPlanes[0], 0, BLOCK);
        Resampler.Result second =
            cores[1].process(inputPlanes[1], 0, 0, true, outputPlanes[1], 0, BLOCK);
        matching(first, second);
        append(first.outputSamplesGenerated);
        if (first.outputSamplesGenerated == 0) coreDrained = true;
      }
      produced = drain(output, offset, capacity);
      if (produced > 0) return produced;
    }
    if (coreDrained) {
      long expected = budget() - epochBudgetStart;
      long difference = epochGenerated - expected;
      if (Math.abs(difference) > roundingAllowance())
        throw new IOException("Unexpected resampler frame discrepancy: " + difference);
      if (pendingFrames > roundingAllowance()) throw new IOException("Excess pending PCM");
      pendingFrames = 0; // At most rounding-only samples past the presentation budget.
      long missing = budget() - emitted;
      if (missing < 0 || missing > roundingAllowance())
        throw new IOException("Unexpected missing presentation frames");
      int zeros = (int) Math.min(missing, capacity);
      java.util.Arrays.fill(output, offset, offset + zeros * 2, 0);
      emitted += zeros;
      return zeros;
    }
    return 0;
  }

  private void startEpoch(int rate) {
    sourceRate = rate;
    epochBudgetStart = emitted;
    epochGenerated = 0;
    if (rate != targetRate)
      cores =
          new Resampler[] {
            new Resampler(true, (double) targetRate / rate),
            new Resampler(true, (double) targetRate / rate)
          };
  }

  private void clearEpoch() {
    sourceRate = 0;
    cores = null;
    flushing = false;
    coreDrained = false;
    pendingFrames = 0;
  }

  private void append(int frames) throws IOException {
    if (pendingFrames + frames > pending.length / 2)
      throw new IOException("Resampler pending limit exceeded");
    for (int i = 0; i < frames; i++)
      for (int c = 0; c < 2; c++) {
        float value = outputPlanes[c][i];
        if (!Float.isFinite(value)) throw new IOException("Non-finite resampler output");
        pending[(pendingFrames + i) * 2 + c] = value;
      }
    pendingFrames += frames;
    epochGenerated += frames;
  }

  private int drain(float[] output, int offset, int capacity) throws IOException {
    long allowed = budget() - emitted;
    if (allowed < 0) throw new IOException("Presentation clock moved backwards");
    int count = (int) Math.min(Math.min(pendingFrames, capacity), allowed);
    System.arraycopy(pending, 0, output, offset, count * 2);
    System.arraycopy(pending, count * 2, pending, 0, (pendingFrames - count) * 2);
    pendingFrames -= count;
    emitted += count;
    return count;
  }

  private void addTime(int frames, int rate) {
    BigInteger r = BigInteger.valueOf(rate);
    BigInteger numerator =
        clockNumerator
            .multiply(r)
            .add(
                BigInteger.valueOf(frames)
                    .multiply(BigInteger.valueOf(targetRate))
                    .multiply(clockDenominator));
    BigInteger denominator = clockDenominator.multiply(r);
    BigInteger gcd = numerator.gcd(denominator);
    clockNumerator = numerator.divide(gcd);
    clockDenominator = denominator.divide(gcd);
  }

  private int roundingAllowance() {
    return 1 + (targetRate + sourceRate - 1) / sourceRate;
  }

  private long budget() {
    return clockNumerator.divide(clockDenominator).longValueExact();
  }

  private static void matching(Resampler.Result a, Resampler.Result b) throws IOException {
    if (a.inputSamplesConsumed != b.inputSamplesConsumed
        || a.outputSamplesGenerated != b.outputSamplesGenerated)
      throw new IOException("Stereo resampler progress mismatch");
  }

  private static void bounds(float[] array, int offset, int frames) {
    Objects.requireNonNull(array);
    if (offset < 0 || frames < 0 || offset > array.length || frames > (array.length - offset) / 2)
      throw new IndexOutOfBoundsException();
  }

  private static void requireRate(int rate) {
    if (rate < 7350 || rate > 384000) throw new IllegalArgumentException("Unsupported sample rate");
  }

  private void requireOpen() throws IOException {
    if (closed) throw new IOException("Resampler closed");
  }

  @Override
  public void close() {
    closed = true;
    clearEpoch();
  }
}
