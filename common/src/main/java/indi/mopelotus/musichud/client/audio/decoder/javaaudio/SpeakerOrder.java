package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.util.*;

/** Explicit decoded-channel speakers, expressed as the existing WAV/OpenAL speaker-mask bits. */
public record SpeakerOrder(int[] sourceBits) {
  public SpeakerOrder {
    sourceBits = sourceBits.clone();
    long mask = 0;
    for (int bit : sourceBits) {
      if (bit < 0 || bit >= 18 || (mask & (1L << bit)) != 0)
        throw new IllegalArgumentException("Invalid/duplicate speaker");
      mask |= 1L << bit;
    }
    if (sourceBits.length < 1 || sourceBits.length > 8) throw new IllegalArgumentException();
  }

  public int[] sourceBits() {
    return sourceBits.clone();
  }

  public long mask() {
    long mask = 0;
    for (int bit : sourceBits) mask |= 1L << bit;
    return mask;
  }

  public static SpeakerOrder forFormat(PcmFormat f) {
    if (f.speakers() != null)
      return new SpeakerOrder(
          f.speakers().orderedSpeakerBits().stream()
              .mapToInt(Integer::numberOfTrailingZeros)
              .toArray());
    if (f.layout() == PcmFormat.Layout.MONO) return new SpeakerOrder(new int[] {2});
    if (f.layout() == PcmFormat.Layout.STEREO) return new SpeakerOrder(new int[] {0, 1});
    if (f.layout() != PcmFormat.Layout.VORBIS)
      throw new IllegalArgumentException("Unknown decoded speaker order; explicit layout required");
    return new SpeakerOrder(
        switch (f.channels()) {
          case 1 -> new int[] {2};
          case 2 -> new int[] {0, 1};
          case 3 -> new int[] {0, 2, 1};
          case 4 -> new int[] {0, 1, 4, 5};
          case 5 -> new int[] {0, 2, 1, 4, 5};
          case 6 -> new int[] {0, 2, 1, 4, 5, 3};
          case 7 -> new int[] {0, 2, 1, 9, 10, 8, 3};
          case 8 -> new int[] {0, 2, 1, 9, 10, 4, 5, 3};
          default -> throw new IllegalArgumentException();
        });
  }

  public byte[] canonical(byte[] interleaved, int sampleBytes) {
    int frameBytes = Math.multiplyExact(sourceBits.length, sampleBytes);
    if (sampleBytes < 1 || sampleBytes > 4 || interleaved.length % frameBytes != 0)
      throw new IllegalArgumentException("Partial speaker frame");
    int[] sorted = sourceBits.clone();
    Arrays.sort(sorted);
    byte[] result = new byte[interleaved.length];
    for (int out = 0; out < sorted.length; out++) {
      int input = 0;
      while (sourceBits[input] != sorted[out]) input++;
      for (int start = 0; start < result.length; start += frameBytes)
        System.arraycopy(
            interleaved,
            start + input * sampleBytes,
            result,
            start + out * sampleBytes,
            sampleBytes);
    }
    return result;
  }
}
