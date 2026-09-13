package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.client.audio.OpenAlFormatSelector;

/** Converts decoded PCM for the device without reopening the decoder or changing its timeline. */
public final class PcmDeviceConversion {
    private PcmDeviceConversion() {}

    public static byte[] convert(byte[] pcm, int inputFormat, int outputFormat, int sampleRate) {
        int inputChannels = OpenAlFormatSelector.channels(inputFormat);
        int outputChannels = OpenAlFormatSelector.channels(outputFormat);
        int width = OpenAlFormatSelector.frameSize(inputFormat) / inputChannels;
        if (pcm.length % OpenAlFormatSelector.frameSize(inputFormat) != 0)
            throw new IllegalArgumentException("Partial PCM frame");
        if (inputFormat == outputFormat) return pcm;
        if (width != 2 && width != 4 || outputChannels != inputChannels && outputChannels != 2)
            throw new IllegalArgumentException("Unsupported device PCM conversion");
        boolean floating = OpenAlFormatSelector.floating(outputFormat);
        if (floating && width != 4) throw new IllegalArgumentException("Cannot increase PCM precision");
        PcmOutput output = new PcmOutput(inputChannels, width * 8, sampleRate, floating,
                OpenAlFormatSelector.channelMask(inputFormat), outputChannels > 2);
        if (output.format() != outputFormat) throw new IllegalArgumentException("Incompatible device PCM format");
        return output.convert(pcm, OpenAlFormatSelector.floating(inputFormat));
    }
}
