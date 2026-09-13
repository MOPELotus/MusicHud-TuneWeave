package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.state.IMusicTrackState;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceState;
import indi.mopelotus.musichud.beans.state.ISubscribeState;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IClientMusicService {
    static IClientMusicService getInstance() {
        Environment currentEnvironment = MusicHud.getCurrentEnvironment();
        if (currentEnvironment.getSide() == Environment.Side.CLIENT) {
            Environment.Platform platform = currentEnvironment.getPlatform();
            Supplier<IClientMusicService> supplier = platform.getClientMusicServiceSupplier();
            if (supplier != null) {
                IClientMusicService iClientMusicService = supplier.get();
                if (iClientMusicService != null) {
                    return iClientMusicService;
                }
            }
        }
        throw new UnsupportedOperationException();
    }

    interface IUserCollections {
        UserCategoryPlaylists getUserCategoryPlaylists();
        ObservableSequencedSet<Album> getSubscribedAlbums();
        ObservableSequencedSet<Artist> getSubscribedArtists();
    }

    IIdlePlaySourceState getIdlePlaySourceState();

    CompletableFuture<Playlist> loadPlaylistDetail(long id, boolean ignoreCache);

    CompletableFuture<Album> loadAlbumDetail(long id, boolean ignoreCache);

    void refreshQueue(Queue<QueueItem> queue);

    /** Captures queue ordering and ownership before dispatching asynchronous publication. */
    Runnable prepareQueueRefresh(Queue<QueueItem> queue);

    void sendPushMusicToQueue(MusicDetail musicDetail);

    void sendRemoveMusicFromQueue(int index, QueueItem item);

    void switchMusic(PlaybackSession playbackSession, MusicDetail nextIdleMusicDetail, String message);

    PlaybackResolution resolvePublicPlayback(MusicDetail requestedMusic);

    CompletableFuture<Artist> loadArtist(long id, boolean ignoreCache);

    CompletableFuture<List<MusicDetail>> loadArtistMusic(long id, int offset);

    void voteForSkipCurrent();

    void keyBindsVoteSkipCurrent();

    CompletableFuture<UserCategoryPlaylists> loadUserPlaylists(boolean ignoreCache);

    CompletableFuture<LinkedHashSet<Album>> loadUserAlbums(boolean ignoreCache);

    CompletableFuture<LinkedHashSet<Artist>> loadUserArtists(boolean ignoreCache);

    CompletableFuture<Artist> loadArtistDetailAsync(Artist artist);

    CompletableFuture<Collection<MusicDetail>> loadMoreMusicOfArtist(Artist artist);

    CompletableFuture<loadMusicCollectionMoreDataResult> loadMoreMusicOfCollection(MusicCollection musicCollection, boolean ignoreCache);

    Queue<QueueItem> getMusicQueue();

    Set<Consumer<Queue<QueueItem>>> getMusicQueueRefreshListeners();

    Set<Consumer<QueueItem>> getMusicQueuePushListeners();

    Set<BiConsumer<Integer, QueueItem>> getMusicQueueRemoveListeners();

    IMusicTrackState getMusicTrackState(MusicDetail musicDetail);

    ISubscribeState<Playlist> getPlaylistSubscribeState(Playlist musicDetail);

    ISubscribeState<Album> getAlbumSubscribeState(Album musicDetail);

    ISubscribeState<Artist> getArtistSubscribedState(Artist musicDetail);

    CompletableFuture<? extends IUserCollections> loadUserCollections(boolean ignoreCache);

    record loadMusicCollectionMoreDataResult(MusicCollection musicCollection, Collection<MusicDetail> musicDetails) {}
}
