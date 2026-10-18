package indi.mopelotus.musichud.audio.codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable interleaved little-endian PCM contract. No implied resampling/downmix. */
public record PcmFormat(
    int sampleRate, int channels, Encoding encoding, Layout layout, SpeakerLayout speakers) {
  public PcmFormat(int sampleRate, int channels, Encoding encoding, Layout layout) {
    this(sampleRate, channels, encoding, layout, null);
  }

  /**
   * Explicit WAVE speaker bits in the order of the PCM channels; never inferred from channel count.
   */
  public record SpeakerLayout(long mask, List<Integer> orderedSpeakerBits) {
    public SpeakerLayout {
      orderedSpeakerBits = List.copyOf(Objects.requireNonNull(orderedSpeakerBits));
      if (mask <= 0
          || (mask & ~0x3ffffL) != 0
          || orderedSpeakerBits.isEmpty()
          || orderedSpeakerBits.size() > 8)
        throw new IllegalArgumentException("Unsupported speaker mask");
      long combined = 0;
      var unique = new HashSet<Integer>();
      for (int bit : orderedSpeakerBits) {
        if (bit <= 0 || Integer.bitCount(bit) != 1 || !unique.add(bit))
          throw new IllegalArgumentException("Invalid speaker order");
        combined |= bit;
      }
      if (combined != mask) throw new IllegalArgumentException("Speaker mask/order mismatch");
    }

    public static SpeakerLayout fromWaveMask(long mask) {
      var order = new ArrayList<Integer>();
      for (int bit = 1; bit <= 0x20000; bit <<= 1) if ((mask & bit) != 0) order.add(bit);
      return new SpeakerLayout(mask, order);
    }
  }

  public enum Encoding {
    S8(1),
    S16LE(2),
    S24LE(3),
    S32LE(4),
    F32LE(4);
    public final int bytes;

    Encoding(int bytes) {
      this.bytes = bytes;
    }
  }

  public enum Layout {
    MONO,
    STEREO,
    VORBIS,
    UNSPECIFIED
  }

  public PcmFormat {
    Objects.requireNonNull(encoding);
    Objects.requireNonNull(layout);
    if (speakers != null && speakers.orderedSpeakerBits().size() != channels)
      throw new IllegalArgumentException("Speaker count mismatch");
    if (sampleRate < 1 || sampleRate > 768000 || channels < 1 || channels > 8)
      throw new IllegalArgumentException("Unsupported PCM dimensions");
    if (layout == Layout.MONO && channels != 1 || layout == Layout.STEREO && channels != 2)
      throw new IllegalArgumentException("Channel layout mismatch");
  }

  public int frameBytes() {
    return Math.multiplyExact(channels, encoding.bytes);
  }
}
