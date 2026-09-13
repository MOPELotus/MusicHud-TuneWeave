package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.state.IMusicTrackState;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.utils.CollectionUpdateNotifier;
import java.util.concurrent.*;
import java.util.function.*;

public class MusicTrackState implements IMusicTrackState {
    private record Key(Object scope, long playlist, String track) {}
    private static final ConcurrentHashMap<Key, CopyOnWriteArrayList<Consumer<Boolean>>> LISTENERS = new ConcurrentHashMap<>();
    private final MusicDetail musicDetail;
    private final Supplier<CompletableFuture<Playlist>> likedLoader;
    private final LongFunction<CompletableFuture<Playlist>> fullLoader;
    private final Function<Boolean, Function<Playlist, Void>> prepareWrite;
    private final Executor executor;

    public MusicTrackState(MusicDetail musicDetail) {
        this(musicDetail, () -> CompletableFuture.supplyAsync(TuneWeaveClientService.getInstance().prepareFavoritePlaylist(musicDetail), MusicHud.EXECUTOR),
                id -> MusicService.getInstance().loadPlaylistDetail(id, false),
                selected -> TuneWeaveClientService.getInstance().prepareFunction(playlist -> {
                    TuneWeaveClientService.getInstance().modifyPlaylistTracks(playlist, musicDetail, selected); return null;
                }), MusicHud.EXECUTOR);
    }

    MusicTrackState(MusicDetail musicDetail, Supplier<CompletableFuture<Playlist>> likedLoader,
                    LongFunction<CompletableFuture<Playlist>> fullLoader,
                    Function<Boolean, Function<Playlist, Void>> prepareWrite, Executor executor) {
        this.musicDetail = musicDetail; this.likedLoader = likedLoader; this.fullLoader = fullLoader;
        this.prepareWrite = prepareWrite; this.executor = executor;
    }

    @Override public IPlaylistSubState currentUsersLikeList() { return new PlaylistSubState(-1); }
    @Override public IPlaylistSubState playlist(long playlistId) { return new PlaylistSubState(playlistId); }

    public final class PlaylistSubState implements IPlaylistSubState {
        private final long originalId;
        private long resolvedId;
        private Object scope;
        PlaylistSubState(long id) { originalId = id; resolvedId = id; }
        @Override public synchronized long playlistId() { return resolvedId; }

        private CompletableFuture<Playlist> loadPlaylist(Object expected) {
            long id;
            synchronized (this) {
                if (scope != expected) { scope = expected; resolvedId = originalId; }
                id = resolvedId;
            }
            return (id == -1 ? likedLoader.get() : fullLoader.apply(id)).thenApply(playlist -> {
                MusicEntityCache.publish(expected, () -> {
                    synchronized (this) { if (scope == expected) resolvedId = playlist.getId(); }
                });
                return playlist;
            });
        }

        @Override public CompletableFuture<Boolean> isContained() {
            Object expected = MusicEntityCache.captureGeneration();
            return loadPlaylist(expected).thenApply(playlist -> playlist.getMusicDetails().stream()
                    .anyMatch(track -> track.getSourceRef().equals(musicDetail.getSourceRef())));
        }
        @Override public CompletableFuture<Void> add() { return modify(true); }
        @Override public CompletableFuture<Void> remove() { return modify(false); }

        private CompletableFuture<Void> modify(boolean selected) {
            Object expected = MusicEntityCache.captureGeneration();
            final Function<Playlist, Void> write;
            try { write = prepareWrite.apply(selected); }
            catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
            return CompletableFuture.runAsync(() -> {
                Playlist playlist = loadPlaylist(expected).join();
                MusicEntityCache.publish(expected, () -> {});
                write.apply(playlist);
                MusicEntityCache.publish(expected, () -> {
                    var tracks = playlist.getMusicDetails();
                    tracks.removeIf(track -> track.getSourceRef().equals(musicDetail.getSourceRef()));
                    if (selected) tracks.addFirst(musicDetail);
                    var listeners = LISTENERS.get(new Key(expected, playlist.getId(), musicDetail.getSourceRef()));
                    if (listeners != null) listeners.forEach(listener -> listener.accept(selected));
                    CollectionUpdateNotifier.notifyPlaylistUpdated(playlist.getId());
                });
            }, executor);
        }
        @Override public Unregister onOthersModify(Consumer<Boolean> listener) {
            Object expected = MusicEntityCache.captureGeneration();
            var registration = new DeferredListener();
            loadPlaylist(expected).thenAccept(playlist -> MusicEntityCache.publish(expected, () -> {
                Key key = new Key(expected, playlist.getId(), musicDetail.getSourceRef());
                LISTENERS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);
                registration.install(() -> LISTENERS.computeIfPresent(key, (ignored, values) -> {
                    values.remove(listener); return values.isEmpty() ? null : values;
                }));
            })).exceptionally(error -> null);
            return registration;
        }
    }

    private static final class DeferredListener implements Unregister {
        private boolean closed;
        private Unregister subscription;
        synchronized void install(Unregister subscription) {
            if (closed) subscription.unregister(); else this.subscription = subscription;
        }
        public synchronized void unregister() {
            if (closed) return;
            closed = true;
            if (subscription != null) { subscription.unregister(); subscription = null; }
        }
    }
}
