package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

class IncrementalPcmResamplerTest {
  static int checks;

  static void yes(boolean good, String description) {
    checks++;
    if (!good) throw new AssertionError(description);
  }

  static float[] stereo(int rate, int frames) {
    float[] f = new float[frames * 2];
    for (int i = 0; i < frames; i++) {
      f[2 * i] = (float) (0.3 * Math.sin(2 * Math.PI * 997 * i / rate));
      f[2 * i + 1] = (float) (0.15 * Math.cos(2 * Math.PI * 631 * i / rate));
    }
    return f;
  }

  static final class Samples {
    float[] data = new float[1024];
    int length;

    void add(float[] a, int frames) {
      if (length + frames * 2 > data.length)
        data = Arrays.copyOf(data, Math.max(data.length * 2, length + frames * 2));
      System.arraycopy(a, 0, data, length, frames * 2);
      length += frames * 2;
    }

    float[] values() {
      return Arrays.copyOf(data, length);
    }
  }

  static float[] run(int target, int[] rates, float[][] epochs, int inputChunk, int capacity)
      throws Exception {
    var result = new Samples();
    float[] output = new float[capacity * 2];
    try (var adapter = new IncrementalPcmResampler(target)) {
      for (int e = 0; e < rates.length; e++) {
        int position = 0;
        int guard = 0;
        while (position < epochs[e].length / 2) {
          var p =
              adapter.process(
                  epochs[e],
                  position * 2,
                  Math.min(inputChunk, epochs[e].length / 2 - position),
                  rates[e],
                  output,
                  0,
                  capacity);
          position += p.inputFramesConsumed();
          result.add(output, p.outputFramesProduced());
          yes(!p.finished(), "input not finished");
          if (++guard > 10000000) throw new AssertionError("progress stalled");
        }
      }
      for (int guard = 0; ; guard++) {
        var p = adapter.finish(output, 0, capacity);
        result.add(output, p.outputFramesProduced());
        if (p.finished()) break;
        if (guard > 10000000) throw new AssertionError("finish stalled");
      }
      yes(adapter.emittedFrames() == result.length / 2, "emitted clock");
      yes(adapter.finish(output, 0, capacity).finished(), "finish idempotent");
    }
    return result.values();
  }

  @org.junit.jupiter.api.Test
  void exactClockAndBackpressureAcrossRateChanges() throws Exception {
    for (int target : new int[] {7350, 44100, 48000, 384000})
      for (int rate : new int[] {7350, 8000, 32000, 44100, 48000, 96000, 192000, 384000}) {
        float[] input = stereo(rate, rate / 5 + 17);
        float[] large = run(target, new int[] {rate}, new float[][] {input}, 1024, 1024);
        float[] small = run(target, new int[] {rate}, new float[][] {input}, 7, 3);
        yes(
            large.length / 2 == (long) (input.length / 2) * target / rate,
            "exact duration " + rate + "->" + target);
        yes(small.length == large.length, "partition length");
        double difference = 0, errorEnergy = 0, signalEnergy = 0;
        for (int i = 0; i < large.length; i++) {
          double error = large[i] - small[i];
          difference = Math.max(difference, Math.abs(error));
          errorEnergy += error * error;
          signalEnergy += large[i] * large[i];
        }
        double partitionSnr = 10 * Math.log10(signalEnergy / Math.max(errorEnergy, 1e-300));
        yes(
            difference < 0.001 && partitionSnr > 70,
            "partition quality " + rate + " -> " + target + " error=" + difference);
        System.out.println(
            "rate="
                + rate
                + " target="
                + target
                + " frames="
                + large.length / 2
                + " partition_max_error="
                + difference
                + " partition_snr_db="
                + partitionSnr);
      }
    int[] rates = {44100, 48000, 32000, 96000, 44100};
    float[][] epochs = new float[rates.length][];
    BigInteger numerator = BigInteger.ZERO, denominator = BigInteger.ONE;
    for (int i = 0; i < rates.length; i++) {
      int frames = 1024 + i;
      epochs[i] = stereo(rates[i], frames);
      numerator =
          numerator
              .multiply(BigInteger.valueOf(rates[i]))
              .add(BigInteger.valueOf(frames * 48000L).multiply(denominator));
      denominator = denominator.multiply(BigInteger.valueOf(rates[i]));
    }
    float[] dynamic = run(48000, rates, epochs, 3, 1);
    yes(
        dynamic.length / 2 == numerator.divide(denominator).longValueExact(),
        "dynamic rational count");
    float[] tiny = run(48000, new int[] {44100}, new float[][] {stereo(44100, 1000)}, 1, 1);
    yes(tiny.length / 2 == 1000L * 48000 / 44100, "one-frame packet carry");
    try (var adapter = new IncrementalPcmResampler(48000)) {
      float[] output = new float[4];
      var zero = adapter.process(new float[0], 0, 0, 44100, output, 0, 2);
      yes(zero.inputFramesConsumed() == 0 && zero.outputFramesProduced() == 0, "zero input");
      var pressure = adapter.process(new float[] {0, 0}, 0, 1, 44100, new float[0], 0, 0);
      yes(pressure.inputFramesConsumed() == 0, "zero output backpressure");
      try {
        adapter.process(new float[] {Float.NaN, 0}, 0, 1, 44100, output, 0, 2);
        throw new AssertionError("NaN accepted");
      } catch (IOException expected) {
        checks++;
      }
      try {
        adapter.process(new float[] {0, Float.POSITIVE_INFINITY}, 0, 1, 44100, output, 0, 2);
        throw new AssertionError("infinity accepted");
      } catch (IOException expected) {
        checks++;
      }
      yes(adapter.finish(output, 0, 2).finished(), "empty finish");
      try {
        adapter.process(new float[] {0, 0}, 0, 1, 44100, output, 0, 2);
        throw new AssertionError("input after finish");
      } catch (IOException expected) {
        checks++;
      }
      adapter.close();
      adapter.close();
      try {
        adapter.finish(output, 0, 2);
        throw new AssertionError("closed finish");
      } catch (IOException expected) {
        checks++;
      }
    }
    System.out.println("IncrementalPcmResampler assertions=" + checks + " PASS");
  }
}
