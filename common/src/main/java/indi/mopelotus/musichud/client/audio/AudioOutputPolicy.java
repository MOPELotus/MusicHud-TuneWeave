package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import java.util.HashMap;
import java.util.Map;

/** Rejection history belongs to a device generation, not a song or an AL source name. */
final class AudioOutputPolicy {
    static final int REJECTIONS_BEFORE_FALLBACK = 3;
    private final Map<Integer, Integer> rejections = new HashMap<>();
    private long deviceGeneration = Long.MIN_VALUE;
    private long context;
    private boolean rejectMultichannel;
    private boolean rejectFloat;

    void device(long context, long generation) {
        if (this.context == context && deviceGeneration == generation) return;
        this.context = context;
        deviceGeneration = generation;
        rejections.clear();
        rejectMultichannel = false;
        rejectFloat = false;
    }

    int format(int input, AudioOutputMode mode, boolean multichannel, boolean floating) {
        int channels = OpenAlFormatSelector.channels(input);
        if (channels > 2 && mode == AudioOutputMode.DISCRETE_ONLY && (!multichannel || rejectMultichannel))
            throw new IllegalStateException("Discrete multichannel output is unavailable on this device");
        if (channels > 2 && (mode == AudioOutputMode.STEREO || !multichannel || rejectMultichannel)) channels = 2;
        return OpenAlFormatSelector.select(channels,
                OpenAlFormatSelector.floating(input) && floating && !rejectFloat);
    }

    boolean rejected(OpenAlFailure failure, int format) {
        if (!failure.formatRejection() || failure.context() != context
                || failure.deviceGeneration() != deviceGeneration) return false;
        if (rejections.merge(format, 1, Integer::sum) < REJECTIONS_BEFORE_FALLBACK) return false;
        if (OpenAlFormatSelector.channels(format) > 2 && !rejectMultichannel) {
            rejectMultichannel = true;
            return true;
        }
        if (OpenAlFormatSelector.floating(format) && !rejectFloat) {
            rejectFloat = true;
            return true;
        }
        return false;
    }

    void accepted(int format) { rejections.remove(format); }
}
