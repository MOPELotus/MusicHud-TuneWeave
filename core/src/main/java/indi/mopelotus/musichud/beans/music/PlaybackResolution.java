package indi.mopelotus.musichud.beans.music;

import java.util.Objects;

/** Credential-free result produced by a client for one public playback resolve. */
public record PlaybackResolution(MusicDetail musicDetail, MusicResourceInfo resourceInfo) {
    public PlaybackResolution {
        Objects.requireNonNull(musicDetail, "musicDetail");
        Objects.requireNonNull(resourceInfo, "resourceInfo");
    }
}
