package indi.mopelotus.musichud.beans.music;

import java.util.Locale;

/** Local device preference; this is never part of the public playback session. */
public enum AudioOutputMode {
    MULTICHANNEL, STEREO, DISCRETE_ONLY;

    public static AudioOutputMode parse(String value) {
        if (value == null) return MULTICHANNEL;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return MULTICHANNEL; }
    }
}
