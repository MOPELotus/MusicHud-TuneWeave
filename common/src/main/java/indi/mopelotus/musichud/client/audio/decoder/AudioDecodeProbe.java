package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.beans.music.FormatType;

public record AudioDecodeProbe(
        String identifier,
        FormatType declaredFormat,
        FormatType detectedFormat,
        String backend,
        int channelCount,
        int sampleRate,
        int openAlFormat,
        int probeBytesRead
) {
}

