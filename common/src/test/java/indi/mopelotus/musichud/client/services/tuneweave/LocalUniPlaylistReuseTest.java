package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.Playlist;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalUniPlaylistReuseTest {
    @Test
    void incompletePlaylistIsNotEligibleForLocalExpansion() {
        Playlist playlist = Playlist.fromTuneWeave(1, "netease:playlist:1", "Partial", "", 2, 0,
                indi.mopelotus.musichud.beans.user.Profile.ANONYMOUS);
        var tracks = new indi.mopelotus.musichud.utils.collections.ObservableSequencedSet<indi.mopelotus.musichud.beans.music.MusicDetail>();
        tracks.add(indi.mopelotus.musichud.beans.music.MusicDetail.fromTuneWeave(2, "netease:track:2", "track", "One", 1,
                indi.mopelotus.musichud.beans.music.Album.NONE, java.util.List.of()));
        playlist.setTracks(tracks);
        assertTrue(playlist.getTracks().size() < playlist.getMusicTrackCount());
    }
}
