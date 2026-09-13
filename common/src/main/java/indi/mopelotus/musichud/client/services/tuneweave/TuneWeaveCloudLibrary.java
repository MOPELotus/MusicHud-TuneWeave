package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.List;

public record TuneWeaveCloudLibrary(List<TuneWeaveCloudTrack> tracks, long total,
                                    long storageSize, long storageMaxSize) {
    public TuneWeaveCloudLibrary {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }
}
