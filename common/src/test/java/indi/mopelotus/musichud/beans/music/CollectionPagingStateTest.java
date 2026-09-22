package indi.mopelotus.musichud.beans.music;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CollectionPagingStateTest {
    @Test void emptyCollectionsKeepTheirObservedBackingSetDuringFirstPageAndNullCleanup() {
        for (MusicCollection collection : List.of(new Album(), new Playlist())) {
            var tracks = collection.getMusicDetails();
            var updates = new AtomicInteger();
            var listener = tracks.registerOnChange(updates::incrementAndGet);
            var track = MusicDetail.fromTuneWeave(1, "netease:1", "track", "One", 1000, Album.NONE, List.of());
            tracks.add(track);
            assertSame(tracks, collection.getMusicDetails());
            assertEquals(List.of(track), collection.getMusicDetails().snapshot());
            tracks.add(null);
            assertSame(tracks, collection.getMusicDetails());
            assertFalse(tracks.contains(null));
            assertEquals(3, updates.get());
            var copy = collection.copyWithPusherInfo(PusherInfo.EMPTY);
            assertEquals(List.of(track), copy.getMusicDetails().snapshot());
            copy.getMusicDetails().clear();
            assertEquals(List.of(track), tracks.snapshot());
            listener.unregister();
        }
    }
}
