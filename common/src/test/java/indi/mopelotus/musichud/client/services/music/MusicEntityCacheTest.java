package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.beans.music.Playlist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MusicEntityCacheTest {
    @Test
    void oldCollectionWarmupCannotInsertAfterScopeChanges() {
        Object old = MusicEntityCache.captureGeneration();
        MusicEntityCache.clear();
        var playlist = new Playlist();
        var album = new indi.mopelotus.musichud.beans.music.Album();
        var artist = new indi.mopelotus.musichud.beans.music.Artist();
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.putPlaylistIfAbsent(old, 21, playlist));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.putAlbumIfAbsent(old, 21, album));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.putArtistIfAbsent(old, 21, artist));
        assertNull(MusicEntityCache.getPlaylist(21));
        assertNull(MusicEntityCache.getAlbum(21));
        assertNull(MusicEntityCache.getArtist(21));
        Object current = MusicEntityCache.captureGeneration();
        org.junit.jupiter.api.Assertions.assertTrue(MusicEntityCache.putPlaylistIfAbsent(current, 21, playlist));
        org.junit.jupiter.api.Assertions.assertTrue(MusicEntityCache.putAlbumIfAbsent(current, 21, album));
        org.junit.jupiter.api.Assertions.assertTrue(MusicEntityCache.putArtistIfAbsent(current, 21, artist));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.putPlaylistIfAbsent(old, 21, new Playlist()));
        org.junit.jupiter.api.Assertions.assertSame(playlist, MusicEntityCache.getPlaylist(21));
    }
    @Test
    void lateDetailCompletionCannotRepopulateClearedCache() {
        Object oldGeneration = MusicEntityCache.captureGeneration();
        var pending = new java.util.concurrent.CompletableFuture<Playlist>();
        var completion = pending.thenApply(value -> {
            MusicEntityCache.publish(oldGeneration, () -> MusicEntityCache.putPlaylist(42, value));
            return value;
        });
        MusicEntityCache.clear();
        Object newGeneration = MusicEntityCache.captureGeneration();
        var current = new Playlist();
        MusicEntityCache.publish(newGeneration, () -> MusicEntityCache.putPlaylist(42, current));
        pending.complete(new Playlist());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class, completion::join);
        org.junit.jupiter.api.Assertions.assertSame(current, MusicEntityCache.getCompletePlaylist(42));
        MusicEntityCache.clear();
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
                () -> MusicEntityCache.publish(newGeneration, () -> MusicEntityCache.putPlaylist(42, current)));
        assertNull(MusicEntityCache.getPlaylist(42));
    }
    @Test
    void freshnessBoundaryAndClockRollbackAreDeterministic() {
        org.junit.jupiter.api.Assertions.assertTrue(MusicEntityCache.isFreshAt(100, 110, 10));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.isFreshAt(100, 111, 10));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.isFreshAt(100, 99, 10));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.isFreshAt(100, 100, -1));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.isFreshAt(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void sameIdInDifferentScopesRetainsDifferentEntities() {
        var firstScope = new AccountScope("netease", "first");
        var secondScope = new AccountScope("netease", "second");
        var first = new Playlist();
        var second = new Playlist();
        MusicEntityCache.putPlaylist(firstScope, 9, first);
        MusicEntityCache.putPlaylist(secondScope, 9, second);
        org.junit.jupiter.api.Assertions.assertSame(first, MusicEntityCache.getPlaylist(firstScope, 9));
        org.junit.jupiter.api.Assertions.assertSame(second, MusicEntityCache.getPlaylist(secondScope, 9));
        MusicEntityCache.clear();
        assertNull(MusicEntityCache.getPlaylist(firstScope, 9));
        assertNull(MusicEntityCache.getPlaylist(secondScope, 9));
    }

    @Test
    void clearRemovesScopedReferenceAndAllCollectionKinds() {
        var scope = new AccountScope("qq", "user");
        MusicEntityCache.putPlaylist(scope, "qq:playlist:1", new Playlist());
        MusicEntityCache.putAlbum(scope, 1, new indi.mopelotus.musichud.beans.music.Album());
        MusicEntityCache.putArtist(scope, 1, new indi.mopelotus.musichud.beans.music.Artist());
        MusicEntityCache.clear();
        assertNull(MusicEntityCache.getPlaylist(scope, "qq:playlist:1"));
        assertNull(MusicEntityCache.getCompleteAlbum(scope, 1));
        assertNull(MusicEntityCache.getArtist(scope, 1));
    }
    @AfterEach
    void clearCache() {
        MusicEntityCache.clear();
    }

    @Test
    void partialEntriesAreNotReportedComplete() {
        Playlist summary = new Playlist();
        MusicEntityCache.putPlaylistIfAbsent(7L, summary);

        assertNotNull(MusicEntityCache.getPlaylist(7L));
        assertNull(MusicEntityCache.getCompletePlaylist(7L));
    }

    @Test
    void clearRemovesMaterializedEntities() {
        MusicEntityCache.putPlaylist(7L, new Playlist());
        MusicEntityCache.clear();

        assertNull(MusicEntityCache.getPlaylist(7L));
    }

    @Test
    void freshnessUsesEntryTimestampAndRejectsNegativeAge() {
        MusicEntityCache.putPlaylist(8L, new Playlist());

        assertNotNull(MusicEntityCache.getPlaylist(8L));
        org.junit.jupiter.api.Assertions.assertTrue(MusicEntityCache.isPlaylistFresh(8L, 1_000));
        org.junit.jupiter.api.Assertions.assertFalse(MusicEntityCache.isPlaylistFresh(8L, -1));
    }
}
