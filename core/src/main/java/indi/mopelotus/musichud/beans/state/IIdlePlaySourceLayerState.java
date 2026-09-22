package indi.mopelotus.musichud.beans.state;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.interfaces.Unregister;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface IIdlePlaySourceLayerState {
    Set<MusicCollection> getSources();

    void add(MusicCollection collection);

    default indi.mopelotus.musichud.beans.api.IdlePlayMode getPlayMode(MusicCollection collection) {
        return indi.mopelotus.musichud.beans.api.IdlePlayMode.RANDOM;
    }

    default void setPlayMode(MusicCollection collection, indi.mopelotus.musichud.beans.api.IdlePlayMode mode) {
        throw new UnsupportedOperationException("Only local sources have editable modes");
    }

    void remove(MusicCollection collection);

    IIdlePlaySourceCollectionState collection(MusicCollection collection);

    Unregister onAdd(Consumer<MusicCollection> listener);

    Unregister onRemove(Consumer<MusicCollection> listener);

    Unregister onChange(Consumer<MusicCollection> listener);

    void loadFromConfig();

    CompletableFuture<? extends MusicCollection> load(Class<?> type, long id);

    void updateAll(List<Playlist> playlistSources, List<Album> albumSources);

    default boolean isInLoadError(Class<?> type, long id) { return false; }

    default Unregister onLoadErrorChanged(Consumer<indi.mopelotus.musichud.beans.api.IdlePlaySource> listener) {
        return () -> {};
    }

    /** Reloads client-owned data and republishes the public source snapshot. */
    default CompletableFuture<Boolean> recover(Class<?> type, long id) {
        return CompletableFuture.completedFuture(false);
    }

    void reset();
}
