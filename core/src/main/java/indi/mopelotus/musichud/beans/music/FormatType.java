package indi.mopelotus.musichud.beans.music;

import java.util.Locale;

public enum FormatType {
    FLAC,
    MP3,
    AUTO,
    WAV,
    OGG,
    AIFF,
    AU,
    AAC,
    M4A,
    OPUS,
    GENERIC;

    public static FormatType fromSerializedName(String input) {
        if (input == null || input.isBlank()) {
            return AUTO;
        }
        String normalized = input.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "");
        if (normalized.contains("FLAC")) {
            return FLAC;
        }
        if (normalized.contains("MP3") || normalized.contains("MPEG")) {
            return MP3;
        }
        if (normalized.equals("WAV") || normalized.equals("WAVE") || normalized.contains("PCM")) {
            return WAV;
        }
        if (normalized.contains("OPUS")) {
            return OPUS;
        }
        if (normalized.contains("VORBIS") || normalized.contains("OGG") || normalized.contains("OGA")) {
            return OGG;
        }
        if (normalized.contains("AIFF") || normalized.contains("AIFC")) {
            return AIFF;
        }
        if (normalized.equals("AU") || normalized.contains("SUNAU")) {
            return AU;
        }
        if (normalized.contains("M4A") || normalized.contains("ALAC") || normalized.contains("MP4A")) {
            return M4A;
        }
        if (normalized.contains("AAC")) {
            return AAC;
        }
        return GENERIC;
    }
}
