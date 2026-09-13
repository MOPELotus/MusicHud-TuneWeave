package indi.mopelotus.musichud.client.services.music;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.Playlist;

import java.util.concurrent.TimeUnit;

/** Shared client-side cache for materialized TuneWeave entities. */
public final class MusicEntityCache {
    public static final long DETAIL_FRESHNESS_MILLIS = 5 * 60 * 1000L;
    private static final Cache<Long, CollectionEntry<Playlist>> PLAYLISTS = cache();
    private static final Cache<Long, CollectionEntry<Album>> ALBUMS = cache();
    private static final Cache<Long, Artist> ARTISTS = cache();
    private static final Cache<ScopedKey, CollectionEntry<Playlist>> SCOPED_PLAYLISTS = cache();
    private static final Cache<ScopedReferenceKey, Playlist> SCOPED_REFERENCE_PLAYLISTS = cache();
    private static final Cache<ScopedKey, CollectionEntry<Album>> SCOPED_ALBUMS = cache();
    private static final Cache<ScopedKey, Artist> SCOPED_ARTISTS = cache();
    private static Object generation = new Object();

    public static synchronized Object captureGeneration() {
        return generation;
    }

    public static synchronized void publish(Object expectedGeneration, Runnable write) {
        if (expectedGeneration != generation) {
            throw new java.util.concurrent.CancellationException("Entity cache scope changed during load");
        }
        write.run();
    }

    private MusicEntityCache() {
    }

    private static <K, T> Cache<K, T> cache() {
        return CacheBuilder.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumSize(50)
                .build();
    }

    public static Playlist getPlaylist(long id) {
        var entry = PLAYLISTS.getIfPresent(id);
        return entry == null ? null : entry.value();
    }

    public static Album getAlbum(long id) {
        var entry = ALBUMS.getIfPresent(id);
        return entry == null ? null : entry.value();
    }

    public static Playlist getCompletePlaylist(long id) {
        var entry = PLAYLISTS.getIfPresent(id);
        return entry != null && entry.complete() && isFresh(entry, DETAIL_FRESHNESS_MILLIS) ? entry.value() : null;
    }

    public static Album getCompleteAlbum(long id) {
        var entry = ALBUMS.getIfPresent(id);
        return entry != null && entry.complete() && isFresh(entry, DETAIL_FRESHNESS_MILLIS) ? entry.value() : null;
    }

    public static Artist getArtist(long id) {
        return ARTISTS.getIfPresent(id);
    }

    public static void putPlaylist(long id, Playlist playlist) {
        PLAYLISTS.put(id, new CollectionEntry<>(playlist, true, System.currentTimeMillis()));
    }

    public static Playlist getPlaylist(AccountScope scope, long id) {
        CollectionEntry<Playlist> entry = SCOPED_PLAYLISTS.getIfPresent(new ScopedKey(scope, id));
        return entry == null ? null : entry.value();
    }

    public static Playlist getCompletePlaylist(AccountScope scope, long id) {
        CollectionEntry<Playlist> entry = SCOPED_PLAYLISTS.getIfPresent(new ScopedKey(scope, id));
        return entry != null && entry.complete() && isFresh(entry, DETAIL_FRESHNESS_MILLIS)
                ? entry.value() : null;
    }

    public static void putPlaylist(AccountScope scope, long id, Playlist playlist) {
        SCOPED_PLAYLISTS.put(new ScopedKey(scope, id),
                new CollectionEntry<>(playlist, true, System.currentTimeMillis()));
    }

    public static Playlist getPlaylist(AccountScope scope, String reference) {
        return SCOPED_REFERENCE_PLAYLISTS.getIfPresent(new ScopedReferenceKey(scope, reference));
    }

    public static void putPlaylist(AccountScope scope, String reference, Playlist playlist) {
        SCOPED_REFERENCE_PLAYLISTS.put(new ScopedReferenceKey(scope, reference), playlist);
    }

    public static void putAlbum(long id, Album album) {
        ALBUMS.put(id, new CollectionEntry<>(album, true, System.currentTimeMillis()));
    }

    public static Album getCompleteAlbum(AccountScope scope, long id) {
        CollectionEntry<Album> entry = SCOPED_ALBUMS.getIfPresent(new ScopedKey(scope, id));
        return entry != null && entry.complete() && isFresh(entry, DETAIL_FRESHNESS_MILLIS) ? entry.value() : null;
    }

    public static void putAlbum(AccountScope scope, long id, Album album) {
        SCOPED_ALBUMS.put(new ScopedKey(scope, id), new CollectionEntry<>(album, true, System.currentTimeMillis()));
    }

    public static Artist getArtist(AccountScope scope, long id) {
        return SCOPED_ARTISTS.getIfPresent(new ScopedKey(scope, id));
    }

    public static void putArtist(AccountScope scope, long id, Artist artist) {
        SCOPED_ARTISTS.put(new ScopedKey(scope, id), artist);
    }

    public static void putArtist(long id, Artist artist) {
        ARTISTS.put(id, artist);
    }

    public static boolean putPlaylistIfAbsent(long id, Playlist playlist) {
        return PLAYLISTS.asMap().putIfAbsent(id, new CollectionEntry<>(playlist, false, System.currentTimeMillis())) == null;
    }

    public static boolean putAlbumIfAbsent(long id, Album album) {
        return ALBUMS.asMap().putIfAbsent(id, new CollectionEntry<>(album, false, System.currentTimeMillis())) == null;
    }

    public static boolean putArtistIfAbsent(long id, Artist artist) {
        return ARTISTS.asMap().putIfAbsent(id, artist) == null;
    }

    static synchronized boolean putPlaylistIfAbsent(Object expected, long id, Playlist value) {
        return expected == generation && putPlaylistIfAbsent(id, value);
    }

    static synchronized boolean putAlbumIfAbsent(Object expected, long id, Album value) {
        return expected == generation && putAlbumIfAbsent(id, value);
    }

    static synchronized boolean putArtistIfAbsent(Object expected, long id, Artist value) {
        return expected == generation && putArtistIfAbsent(id, value);
    }

    /** Drops materialized entities when the TuneWeave account scope changes. */
    public static synchronized void clear() {
        generation = new Object();
        PLAYLISTS.invalidateAll();
        SCOPED_PLAYLISTS.invalidateAll();
        SCOPED_REFERENCE_PLAYLISTS.invalidateAll();
        SCOPED_ALBUMS.invalidateAll();
        SCOPED_ARTISTS.invalidateAll();
        ALBUMS.invalidateAll();
        ARTISTS.invalidateAll();
    }

    // Summary/partial entries remain available for display, but only a successful
    // detail load may promote an entry to complete. Track count is not evidence.
    public static boolean isPlaylistFresh(long id, long maxAgeMillis) {
        var entry = PLAYLISTS.getIfPresent(id);
        return entry != null && isFresh(entry, maxAgeMillis);
    }

    public static boolean isAlbumFresh(long id, long maxAgeMillis) {
        var entry = ALBUMS.getIfPresent(id);
        return entry != null && isFresh(entry, maxAgeMillis);
    }

    private static boolean isFresh(CollectionEntry<?> entry, long maxAgeMillis) {
        return isFreshAt(entry.cachedAtMillis(), System.currentTimeMillis(), maxAgeMillis);
    }

    static boolean isFreshAt(long cachedAtMillis, long nowMillis, long maxAgeMillis) {
        if (maxAgeMillis < 0 || nowMillis < cachedAtMillis) return false;
        long age = nowMillis - cachedAtMillis;
        return age >= 0 && age <= maxAgeMillis;
    }

    private record CollectionEntry<T>(T value, boolean complete, long cachedAtMillis) {
        private CollectionEntry {
            java.util.Objects.requireNonNull(value);
        }
    }

    private record ScopedKey(AccountScope scope, long entityId) {
        private ScopedKey {
            java.util.Objects.requireNonNull(scope);
        }
    }

    private record ScopedReferenceKey(AccountScope scope, String reference) {
        private ScopedReferenceKey {
            java.util.Objects.requireNonNull(scope);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference");
        }
    }
}
