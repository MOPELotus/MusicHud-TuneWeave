package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL;

import java.util.Set;

/** Chooses an OpenAL PCM format without requiring an extension at class load time. */
public final class OpenAlFormatSelector {
    public static final int AL_FORMAT_MONO_FLOAT32 = 0x10010;
    public static final int AL_FORMAT_STEREO_FLOAT32 = 0x10011;
    private OpenAlFormatSelector() {}

    public static int select(int channels, boolean float32Supported) {
        if (channels > 2) return switch (channels) {
            case 4 -> float32Supported ? 0x1206 : 0x1205;
            case 6 -> float32Supported ? 0x120c : 0x120b;
            case 7 -> float32Supported ? 0x120f : 0x120e;
            case 8 -> float32Supported ? 0x1212 : 0x1211;
            default -> throw new IllegalArgumentException("channels");
        };
        if (channels < 1) throw new IllegalArgumentException("channels");
        if (float32Supported) return channels == 1 ? AL_FORMAT_MONO_FLOAT32 : AL_FORMAT_STEREO_FLOAT32;
        return channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
    }

    public static boolean nativeMultichannelLayout(int channels, long mask, boolean supported) {
        return supported && switch (channels) {
            case 4 -> mask == 0x33;
            case 6 -> mask == 0x3f;
            case 7 -> mask == 0x70f;
            case 8 -> mask == 0x63f;
            default -> false;
        };
    }

    public static int channels(int format) {
        return switch (format) {
            case AL10.AL_FORMAT_MONO8, AL10.AL_FORMAT_MONO16, AL_FORMAT_MONO_FLOAT32 -> 1;
            case AL10.AL_FORMAT_STEREO8, AL10.AL_FORMAT_STEREO16, AL_FORMAT_STEREO_FLOAT32 -> 2;
            case 0x1205, 0x1206 -> 4;
            case 0x120b, 0x120c -> 6;
            case 0x120e, 0x120f -> 7;
            case 0x1211, 0x1212 -> 8;
            default -> throw new IllegalArgumentException("Unknown OpenAL PCM format");
        };
    }

    public static int frameSize(int format) {
        int width = switch (format) {
            case AL10.AL_FORMAT_MONO8, AL10.AL_FORMAT_STEREO8 -> 1;
            case AL_FORMAT_MONO_FLOAT32, AL_FORMAT_STEREO_FLOAT32, 0x1206, 0x120c, 0x120f, 0x1212 -> 4;
            default -> 2;
        };
        return channels(format) * width;
    }

    public static boolean directChannels(int format) { return channels(format) <= 2; }

    public static boolean floating(int format) {
        return frameSize(format) / channels(format) == 4;
    }

    public static long channelMask(int format) {
        return switch (channels(format)) {
            case 1 -> 4;
            case 2 -> 3;
            case 4 -> 0x33;
            case 6 -> 0x3f;
            case 7 -> 0x70f;
            case 8 -> 0x63f;
            default -> throw new IllegalArgumentException("Unknown OpenAL channel layout");
        };
    }

    public static int select(AudioDecoder.SampleEncoding encoding, int channels,
                             boolean float32Supported) {
        if (encoding == null) throw new IllegalArgumentException("encoding");
        return select(channels, float32Supported
                && encoding == AudioDecoder.SampleEncoding.PCM_F32_LE);
    }

    public static boolean supportsFloat32(Set<String> extensions) {
        return extensions != null && extensions.stream().anyMatch(value ->
                "AL_EXT_FLOAT32".equalsIgnoreCase(value));
    }

    public static boolean supportsFloat32() {
        return AL.getCapabilities().AL_EXT_FLOAT32;
    }
}
