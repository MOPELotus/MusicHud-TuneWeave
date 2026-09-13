package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalUniTrackSnapshotTest {
    @Test void localMaterializationRetainsArtistsWithoutDependingOnMapperCache() {
        var artist = new Artist(1, "Artist", "", 0, 0, "", new ArrayList<>(), 0, "netease:1");
        var track = MusicDetail.fromTuneWeave(2, "netease:2", "track", "Song", 1000,
                Album.NONE, List.of(artist));
        var item = TuneWeaveUniPlaylistService.materializeLocalTrack(track, 3);
        var snapshot = item.getAsJsonObject("snapshot");
        assertEquals("netease:2", item.get("source_ref").getAsString());
        assertEquals(3, item.get("position").getAsInt());
        assertEquals("Song", snapshot.get("title").getAsString());
        assertEquals("Artist", snapshot.getAsJsonArray("artists").get(0).getAsString());
        assertEquals(1000, snapshot.get("duration_ms").getAsInt());
    }

    @Test void emptyArtistListIsPreservedAndInvalidIdentityIsRejected() {
        var track = MusicDetail.fromTuneWeave(2, "netease:2", "track", "Song", 1000,
                Album.NONE, List.of());
        assertTrue(TuneWeaveUniPlaylistService.materializeLocalTrack(track, 0)
                .getAsJsonObject("snapshot").getAsJsonArray("artists").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> TuneWeaveUniPlaylistService.materializeLocalTrack(track, -1));
        assertThrows(IllegalArgumentException.class,
                () -> TuneWeaveUniPlaylistService.materializeLocalTrack(MusicDetail.NONE, 0));
    }
}
