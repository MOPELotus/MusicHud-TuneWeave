package indi.mopelotus.musichud.audio.codec;

import java.util.Objects;

/** Explicit precision conversion only; preserves channel order and sample rate. */
public final class PcmConversions {
  private PcmConversions() {}

  public static byte[] toS16(byte[] input, PcmFormat format) {
    Objects.requireNonNull(input);
    if (input.length % format.frameBytes() != 0)
      throw new IllegalArgumentException("Partial PCM frame");
    int width = format.encoding().bytes, n = input.length / width;
    byte[] output = new byte[Math.multiplyExact(n, 2)];
    for (int i = 0; i < n; i++) {
      int p = i * width, v;
      switch (format.encoding()) {
        case S8 -> v = input[p] << 8;
        case S16LE -> v = (short) ((input[p] & 255) | (input[p + 1] << 8));
        case S24LE ->
            v = ((input[p] & 255) | ((input[p + 1] & 255) << 8) | (input[p + 2] << 16)) >> 8;
        case S32LE ->
            v =
                ((input[p] & 255)
                        | ((input[p + 1] & 255) << 8)
                        | ((input[p + 2] & 255) << 16)
                        | (input[p + 3] << 24))
                    >> 16;
        case F32LE -> {
          int bits =
              (input[p] & 255)
                  | ((input[p + 1] & 255) << 8)
                  | ((input[p + 2] & 255) << 16)
                  | (input[p + 3] << 24);
          float f = Float.intBitsToFloat(bits);
          v = Float.isNaN(f) ? 0 : Math.max(-32768, Math.min(32767, Math.round(f * 32768f)));
        }
        default -> throw new AssertionError();
      }
      output[2 * i] = (byte) v;
      output[2 * i + 1] = (byte) (v >> 8);
    }
    return output;
  }
}
