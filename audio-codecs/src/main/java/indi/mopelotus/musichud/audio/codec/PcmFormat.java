package indi.mopelotus.musichud.audio.codec;

import java.util.Objects;

/** Immutable interleaved little-endian PCM contract. No implied resampling/downmix. */
public record PcmFormat(int sampleRate, int channels, Encoding encoding, Layout layout) {
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
    if (sampleRate < 1 || sampleRate > 768000 || channels < 1 || channels > 8)
      throw new IllegalArgumentException("Unsupported PCM dimensions");
    if (layout == Layout.MONO && channels != 1 || layout == Layout.STEREO && channels != 2)
      throw new IllegalArgumentException("Channel layout mismatch");
  }

  public int frameBytes() {
    return Math.multiplyExact(channels, encoding.bytes);
  }
}
