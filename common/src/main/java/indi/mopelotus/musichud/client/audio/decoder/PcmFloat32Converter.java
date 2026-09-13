package indi.mopelotus.musichud.client.audio.decoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts signed little-endian PCM samples to little-endian IEEE-754 float samples. */
public final class PcmFloat32Converter {
    private PcmFloat32Converter() {}

    public static byte[] from24Le(byte[] input) {
        if (input == null || input.length % 3 != 0) throw new IllegalArgumentException("24-bit PCM alignment");
        byte[] output = new byte[input.length / 3 * 4];
        ByteBuffer floats = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < input.length; i += 3) {
            int value = (input[i] & 0xff) | ((input[i + 1] & 0xff) << 8) | (input[i + 2] << 16);
            if ((value & 0x00800000) != 0) value |= 0xff000000;
            floats.putFloat(Math.clamp(value / 8388608.0f, -1.0f, 1.0f));
        }
        return output;
    }

    public static byte[] from32Le(byte[] input) {
        if (input == null || input.length % 4 != 0) throw new IllegalArgumentException("32-bit PCM alignment");
        byte[] output = new byte[input.length];
        ByteBuffer source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        while (source.hasRemaining()) target.putFloat(Math.clamp(source.getInt() / 2147483648.0f, -1.0f, 1.0f));
        return output;
    }
}
