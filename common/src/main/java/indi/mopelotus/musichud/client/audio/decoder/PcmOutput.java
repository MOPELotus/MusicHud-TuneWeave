package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.client.audio.OpenAlFormatSelector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts native samples only at the final OpenAL boundary. */
final class PcmOutput {
    final int channels, bits, sampleRate;
    final boolean floating;
    private final int outputChannels;
    private final double[][] mix;

    PcmOutput(int channels, int bits, int sampleRate, boolean floatSupported) {
        this(channels, bits, sampleRate, floatSupported, channels == 1 ? 4 : channels == 2 ? 3 : 0);
    }

    PcmOutput(int channels, int bits, int sampleRate, boolean floatSupported, long mask) {
        this(channels, bits, sampleRate, floatSupported, mask, false);
    }

    PcmOutput(int channels, int bits, int sampleRate, boolean floatSupported, long mask, boolean multichannelSupported) {
        if (channels < 1 || channels > 8 || bits < 4 || bits > 32
                || sampleRate < 8000 || sampleRate > 384000) throw new IllegalArgumentException("Unsupported PCM format");
        try { PcmChannelLayout.validate(channels, mask, true); }
        catch (java.io.IOException error) { throw new IllegalArgumentException(error.getMessage()); }
        this.channels = channels; this.bits = bits; this.sampleRate = sampleRate;
        floating = floatSupported && bits > 16;
        boolean passthrough = channels == 1 && mask == 4 || channels == 2 && mask == 3
                || OpenAlFormatSelector.nativeMultichannelLayout(channels, mask, multichannelSupported);
        outputChannels = passthrough ? channels : 2;
        mix = passthrough ? null : matrix(channels, mask);
    }

    int frameSize() { return outputChannels * (floating ? 4 : 2); }
    int format() { return OpenAlFormatSelector.select(outputChannels, floating); }

    byte[] convert(byte[] raw, boolean ieeeFloat) {
        if (bits % 8 != 0) throw new IllegalArgumentException("Raw PCM must be byte aligned");
        int inputBytes = bits / 8;
        if (raw.length % (inputBytes * channels) != 0) throw new IllegalArgumentException("Partial PCM frame");
        ByteBuffer output = ByteBuffer.allocate(raw.length / (inputBytes * channels) * frameSize()).order(ByteOrder.LITTLE_ENDIAN);
        double[] frame = mix == null ? null : new double[channels];
        for (int offset = 0; offset < raw.length; offset += inputBytes) {
            int sample = 0;
            for (int i = 0; i < inputBytes; i++) sample |= (raw[offset + i] & 255) << (i * 8);
            sample = sample << (32 - bits) >> (32 - bits);
            if (ieeeFloat) {
                float value = Float.intBitsToFloat(sample);
                if (!Float.isFinite(value)) value = 0;
                value = Math.clamp(value, -1, 1);
                if (mix == null) putNormalized(output, value);
                else frame[offset / inputBytes % channels] = value;
            } else if (mix == null) put(output, sample);
            else frame[offset / inputBytes % channels] = sample / Math.scalb(1.0, bits - 1);
            if (mix != null && (offset / inputBytes + 1) % channels == 0) mixFrame(output, frame);
        }
        return output.array();
    }

    void put(ByteBuffer output, int sample) {
        if (floating) output.putFloat((float) (sample / Math.scalb(1.0, bits - 1)));
        else output.putShort((short) (bits < 16 ? sample << (16 - bits) : sample >> (bits - 16)));
    }

    void putFrame(ByteBuffer output, int[] samples, double[] scratch) {
        if (mix == null) { for (int sample : samples) put(output, sample); return; }
        for (int channel = 0; channel < channels; channel++) scratch[channel] = samples[channel] / Math.scalb(1.0, bits - 1);
        mixFrame(output, scratch);
    }

    private void mixFrame(ByteBuffer output, double[] samples) {
        for (int side = 0; side < 2; side++) {
            double sum = 0;
            for (int channel = 0; channel < channels; channel++) sum += samples[channel] * mix[side][channel];
            putNormalized(output, sum);
        }
    }

    private void putNormalized(ByteBuffer output, double value) {
        value = Math.clamp(value, -1, 1);
        if (floating) output.putFloat((float) value);
        else output.putShort((short) Math.clamp(Math.round(value * 32768), -32768, 32767));
    }

    /** Deterministic stereo fold-down, not an HRTF renderer. Global headroom avoids clipping
     * even with coherent full-scale channels; equal gain preserves the left/right balance. */
    private static double[][] matrix(int channels, long mask) {
        double[][] weights = new double[2][channels];
        double surround = Math.sqrt(.5);
        int channel = 0;
        for (int bit = 0; bit < 18; bit++) {
            if ((mask & (1L << bit)) == 0) continue;
            switch (bit) {
                case 0 -> weights[0][channel] = 1;
                case 1 -> weights[1][channel] = 1;
                case 4, 6, 9, 12, 15 -> weights[0][channel] = surround;
                case 5, 7, 10, 14, 17 -> weights[1][channel] = surround;
                case 3 -> { weights[0][channel] = .5; weights[1][channel] = .5; }
                default -> { weights[0][channel] = surround; weights[1][channel] = surround; }
            }
            channel++;
        }
        double gain = 1;
        for (double[] side : weights) {
            double sum = 0; for (double weight : side) sum += weight;
            gain = Math.max(gain, sum);
        }
        for (double[] side : weights) for (int i = 0; i < channels; i++) side[i] /= gain;
        return weights;
    }
}
