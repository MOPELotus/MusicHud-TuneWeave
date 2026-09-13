package indi.mopelotus.musichud.client.services.music;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.RequestResponseManager;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.RotateNextToPlayRequest;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.RotateNextToPlayResponse;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceState;
import indi.mopelotus.musichud.beans.state.IMusicTrackState;
import indi.mopelotus.musichud.beans.state.ISubscribeState;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.client.interfaces.IClientEventService;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.music.states.*;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.utils.IClientDistUtil;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.ClientRegister;
import indi.mopelotus.musichud.interfaces.IClientLoginService;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ClientPushMusicToQueueMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ClientRemoveMusicFromQueueMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.VoteSkipCurrentMusicMessage;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.CollectionUpdateNotifier;
import lombok.*;
import net.minecraft.client.resources.language.I18n;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MusicService implements IClientMusicService {
    private static final IClientNetworkService clientNetworkService = IClientNetworkService.getInstance();
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static volatile MusicService instance;
    private static final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();

    @Getter(lazy = true)
    private final IIdlePlaySourceState idlePlaySourceState = new IdlePlaySourceState();
    @Getter
    private final Queue<QueueItem> musicQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final indi.mopelotus.musichud.client.services.music.states.QueueSnapshotPublication queuePublication =
            new indi.mopelotus.musichud.client.services.music.states.QueueSnapshotPublication(this, this::refreshQueue);
    @Getter
    private final Set<Consumer<Queue<QueueItem>>> musicQueueRefreshListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<QueueItem>> musicQueuePushListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<BiConsumer<Integer, QueueItem>> musicQueueRemoveListeners = ConcurrentHashMap.newKeySet();
    private final Set<Consumer<Boolean>> favoriteIntelligenceStateListeners = ConcurrentHashMap.newKeySet();
    private final Deque<MusicDetail> favoriteIntelligenceBuffer = new ArrayDeque<>();
    private final FavoriteIntelligenceState favoriteIntelligenceState = new FavoriteIntelligenceState();
    private volatile Playlist favoriteIntelligencePlaylist;
    private volatile boolean favoriteIntelligenceEnabled;
    private volatile boolean favoriteIntelligencePushPending;
    private volatile String favoriteIntelligenceLastReference = "";
    private final PublicPlaybackState publicPlayback = new PublicPlaybackState(this, new PublicPlaybackState.Output() {
        public void publish(PlaybackSession session, MusicDetail next) {
            if (clientConfig.getEnable()) publishPublicPlayback(session, next);
        }
        public void preview(MusicDetail next) {
            if (clientConfig.getEnable()) NowPlayingInfo.getInstance().updateNextToPlayIdle(next);
        }
        public CompletableFuture<?> play(PlaybackSession session) {
            return clientConfig.getEnable() ? StreamAudioPlayer.getInstance().playSessionAsync(session)
                    : CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<?> restart(PlaybackSession session) {
            if (clientConfig.getEnable()) return StreamAudioPlayer.getInstance().restartSessionAsync(session);
            stop();
            return CompletableFuture.completedFuture(null);
        }
        public void stop() { StreamAudioPlayer.getInstance().stop(); }
        public void failed(PlaybackSession session) {
            var decor = UIManager.getInstance().getDecorView();
            if (decor != null) ToastUtil.show(Toast.makeText(decor.getContext(),
                    I18n.get(MusicHud.MOD_ID + ".text.failedToLoadMusicResource"), Toast.LENGTH_SHORT));
        }
    }, MuiModApi::postToUiThread);
    long lastPressTime = 0;
    private final AccountModulesByPlatform<UserCategoryPlaylists, ObservableSequencedSet<Album>,
            ObservableSequencedSet<Artist>> userCollections = new AccountModulesByPlatform<>();

    private MusicService() {
        musicQueueRefreshListeners.add(this::onQueueRefreshedForFavoriteIntelligence);
    }

    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PACKAGE)
    public static class UserCollections implements IUserCollections {
        private final Object cacheGeneration = MusicEntityCache.captureGeneration();
        @Setter
        private UserCategoryPlaylists userCategoryPlaylists;
        @Setter
        private ObservableSequencedSet<Album> subscribedAlbums;
        @Setter
        private ObservableSequencedSet<Artist> subscribedArtists;
        private volatile long lastReupdateCachesTimestamp = 0;

        public UserCategoryPlaylists getUserCategoryPlaylists() {
            reupdateCachesAsync();
            return userCategoryPlaylists;
        }

        public ObservableSequencedSet<Album> getSubscribedAlbums() {
            reupdateCachesAsync();
            return subscribedAlbums;
        }

        public ObservableSequencedSet<Artist> getSubscribedArtists() {
            reupdateCachesAsync();
            return subscribedArtists;
        }

        private void reupdateCachesAsync() {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastReupdateCachesTimestamp >= 60000) {
                lastReupdateCachesTimestamp = currentTimeMillis;
                MusicHud.EXECUTOR.submit(() -> {
                    if (userCategoryPlaylists != null) {
                        Playlist likeList = userCategoryPlaylists.getLikeList();
                        if (MusicEntityCache.putPlaylistIfAbsent(cacheGeneration, likeList.getId(), likeList)) {
                            CollectionUpdateNotifier.notifyPlaylistUpdated(likeList.getId());
                        }
                        userCategoryPlaylists.getCreatedPlaylist()
                                .forEach(playlist -> {
                                    if (playlist.getMusicDetails() != null && playlist.getMusicDetails().size() == playlist.getMusicTrackCount()
                                            && MusicEntityCache.putPlaylistIfAbsent(cacheGeneration, playlist.getId(), playlist)) {
                                        CollectionUpdateNotifier.notifyPlaylistUpdated(playlist.getId());
                                    }
                                });
                        userCategoryPlaylists.getSubscribedPlaylist()
                                .forEach(playlist -> {
                                    if (playlist.getMusicDetails() != null && playlist.getMusicDetails().size() == playlist.getMusicTrackCount()
                                            && MusicEntityCache.putPlaylistIfAbsent(cacheGeneration, playlist.getId(), playlist)) {
                                        CollectionUpdateNotifier.notifyPlaylistUpdated(playlist.getId());
                                    }
                                });
                    }
                    if (subscribedAlbums != null) {
                        subscribedAlbums.forEach(album -> {
                            if (album.getMusicDetails() != null && album.getMusicDetails().size() == album.getMusicTrackCount()
                                    && MusicEntityCache.putAlbumIfAbsent(cacheGeneration, album.getId(), album)) {
                                CollectionUpdateNotifier.notifyAlbumUpdated(album.getId());
                            }
                        });
                    }
                    if (subscribedArtists != null) {
                        subscribedArtists.forEach(artist -> {
                            if (artist.getMusicDetails() != null && !artist.getMusicDetails().isEmpty() && !artist.getDescription().isEmpty()) {
                                MusicEntityCache.putArtistIfAbsent(cacheGeneration, artist.getId(), artist);
                            }
                        });
                    }
                });
            }
        }
    }


    public static MusicService getInstance() {
        if (instance == null) {
            synchronized (MusicService.class) {
                if (instance == null) {
                    instance = new MusicService();
                }
            }
        }
        return instance;
    }

    public boolean isFavoriteIntelligenceEnabled(Playlist playlist) {
        Playlist active = favoriteIntelligencePlaylist;
        return favoriteIntelligenceEnabled && active != null && playlist != null
                && active.getSourceRef().equals(playlist.getSourceRef());
    }

    public Unregister onFavoriteIntelligenceStateChange(Consumer<Boolean> listener) {
        favoriteIntelligenceStateListeners.add(listener);
        return () -> favoriteIntelligenceStateListeners.remove(listener);
    }

    public synchronized CompletableFuture<Boolean> setFavoriteIntelligenceEnabled(Playlist playlist, boolean enabled) {
        if (!enabled) {
            disableFavoriteIntelligence();
            return CompletableFuture.completedFuture(false);
        }
        if (!tuneWeave.supportsFavoriteIntelligence(playlist)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Favorite intelligence is unavailable for this playlist"));
        }
        long generation = favoriteIntelligenceState.enable(playlist);
        synchronized (favoriteIntelligenceBuffer) {
            favoriteIntelligencePlaylist = playlist;
            favoriteIntelligenceEnabled = true;
            favoriteIntelligencePushPending = false;
            favoriteIntelligenceLastReference = "";
            favoriteIntelligenceBuffer.clear();
        }
        return refillFavoriteIntelligence(generation, playlist).thenApply(ignored -> {
            synchronized (this) {
            if (!favoriteIntelligenceState.accepts(generation, playlist)) {
                throw new java.util.concurrent.CancellationException("Favorite intelligence was superseded");
            }
            notifyFavoriteIntelligenceState(true);
            queueNextFavoriteIntelligenceTrack();
            return true;
            }
        }).exceptionallyCompose(error -> {
            disableFavoriteIntelligenceIfCurrent(generation, playlist);
            return CompletableFuture.failedFuture(error);
        });
    }

    private CompletableFuture<Void> refillFavoriteIntelligence() {
        Playlist playlist = favoriteIntelligencePlaylist;
        if (playlist == null) return CompletableFuture.completedFuture(null);
        return refillFavoriteIntelligence(favoriteIntelligenceState.generation(), playlist);
    }

    private CompletableFuture<Void> refillFavoriteIntelligence(long generation, Playlist playlist) {
        if (!favoriteIntelligenceEnabled || playlist == null
                || !favoriteIntelligenceState.beginLoad(generation, playlist)) {
            return CompletableFuture.completedFuture(null);
        }
        String start = favoriteIntelligenceLastReference;
        return CompletableFuture.supplyAsync(
                tuneWeave.prepareRequest(() -> tuneWeave.loadFavoriteIntelligence(playlist, start)), MusicHud.EXECUTOR)
                .thenAccept(tracks -> {
                    if (tracks.isEmpty()) {
                        throw new IllegalStateException("TuneWeave returned an empty favorite intelligence queue");
                    }
                    synchronized (favoriteIntelligenceBuffer) {
                        if (!favoriteIntelligenceState.accepts(generation, playlist)) {
                            throw new java.util.concurrent.CancellationException("Favorite intelligence was superseded");
                        }
                        for (MusicDetail track : tracks) {
                            if (!track.getSourceRef().equals(favoriteIntelligenceLastReference)) {
                                favoriteIntelligenceBuffer.addLast(track);
                            }
                        }
                    }
                }).whenComplete((ignored, error) -> favoriteIntelligenceState.endLoad(generation));
    }

    private void onQueueRefreshedForFavoriteIntelligence(Queue<QueueItem> queue) {
        if (!favoriteIntelligenceEnabled) return;
        if (!queue.isEmpty()) {
            favoriteIntelligencePushPending = false;
        } else if (!favoriteIntelligencePushPending) {
            queueNextFavoriteIntelligenceTrack();
        }
    }

    private synchronized void queueNextFavoriteIntelligenceTrack() {
        if (!favoriteIntelligenceEnabled || favoriteIntelligencePushPending || !musicQueue.isEmpty()) return;
        MusicDetail next;
        synchronized (favoriteIntelligenceBuffer) {
            next = favoriteIntelligenceBuffer.pollFirst();
        }
        if (next == null) {
            if (favoriteIntelligenceState.isLoading()) return;
            long generation = favoriteIntelligenceState.generation();
            Playlist source = favoriteIntelligencePlaylist;
            refillFavoriteIntelligence(generation, source).thenRun(() -> {
                        if (favoriteIntelligenceState.accepts(generation, source)) queueNextFavoriteIntelligenceTrack();
                    })
                    .exceptionally(error -> {
                        MusicHud.LOGGER.warn("Failed to refill favorite intelligence queue", error);
                        disableFavoriteIntelligenceIfCurrent(generation, source);
                        return null;
                    });
            return;
        }
        favoriteIntelligenceLastReference = next.getSourceRef();
        favoriteIntelligencePushPending = true;
        sendPushMusicToQueue(next.withPlaybackSource(PlaybackSource.from(favoriteIntelligencePlaylist, "INTELLIGENT")));
    }

    private synchronized void disableFavoriteIntelligenceIfCurrent(long generation, Playlist source) {
        if (favoriteIntelligenceState.accepts(generation, source)) disableFavoriteIntelligence();
    }

    private synchronized void disableFavoriteIntelligence() {
        favoriteIntelligenceState.disable();
        favoriteIntelligenceEnabled = false;
        favoriteIntelligencePlaylist = null;
        favoriteIntelligencePushPending = false;
        favoriteIntelligenceLastReference = "";
        synchronized (favoriteIntelligenceBuffer) {
            favoriteIntelligenceBuffer.clear();
        }
        notifyFavoriteIntelligenceState(false);
    }

    private void notifyFavoriteIntelligenceState(boolean enabled) {
        favoriteIntelligenceStateListeners.forEach(listener -> listener.accept(enabled));
    }

    public static void resetCurrentMusicStatus() {
        if (instance != null) {
            instance.disableFavoriteIntelligence();
            instance.resetPublicPlayback();
            instance.getIdlePlaySourceState().local().reset();
            instance.getIdlePlaySourceState().external().reset();
            synchronized (instance) {
                instance.queuePublication.invalidate();
                instance.musicQueue.clear();
            }
        }
        if (HudRendererManager.isLoaded()) {
            HudRendererManager.getInstance().reset();
        }
    }

    /** Account data can be invalidated without erasing the server's queue or session ordering. */
    public synchronized void recoverPlaybackAfterLogout() {
        disableFavoriteIntelligence();
        getIdlePlaySourceState().local().reset();
        publicPlayback.recoverLocalPlayback();
    }

    @Override
    public CompletableFuture<Playlist> loadPlaylistDetail(long id, boolean ignoreCache) {
        return loadPlaylistDetail(id, ignoreCache, ignored -> {});
    }

    public CompletableFuture<Playlist> loadPlaylistDetail(long id, boolean ignoreCache, java.util.function.Consumer<Playlist> progress) {
        Object cacheGeneration = MusicEntityCache.captureGeneration();
        AccountScope accountScope = tuneWeave.collectionAccountScope(id, false);
        if (!ignoreCache) {
            Playlist scoped = MusicEntityCache.getCompletePlaylist(accountScope, id);
            if (scoped != null) return CompletableFuture.completedFuture(scoped);
        }
        if (tuneWeave.isAvailable()) {
            if (!tuneWeave.hasPlaylist(id)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Unknown TuneWeave playlist reference: " + id));
            }
            return CompletableFuture.supplyAsync(tuneWeave.prepareRequest(() -> tuneWeave.loadPlaylistDetail(id, ignoreCache,
                            partial -> MusicEntityCache.publish(cacheGeneration, () -> progress.accept(partial)))), MusicHud.EXECUTOR)
                    .thenApply(playlist -> {
                        MusicEntityCache.publish(cacheGeneration, () -> {
                            MusicEntityCache.putPlaylist(id, playlist);
                            MusicEntityCache.putPlaylist(accountScope, id, playlist);
                            MusicEntityCache.putPlaylist(accountScope, playlist.getSourceRef(), playlist);
                        });
                        return playlist;
                    });
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave is unavailable"));
    }

    @Override
    public CompletableFuture<Album> loadAlbumDetail(long id, boolean ignoreCache) {
        return loadAlbumDetail(id, ignoreCache, ignored -> {});
    }

    public CompletableFuture<Album> loadAlbumDetail(long id, boolean ignoreCache, java.util.function.Consumer<Album> progress) {
        Object cacheGeneration = MusicEntityCache.captureGeneration();
        AccountScope accountScope = tuneWeave.collectionAccountScope(id, true);
        if (!ignoreCache) {
            Album scoped = MusicEntityCache.getCompleteAlbum(accountScope, id);
            if (scoped != null) return CompletableFuture.completedFuture(scoped);
        }
        if (tuneWeave.isAvailable()) {
            if (!tuneWeave.hasAlbum(id)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Unknown TuneWeave album reference: " + id));
            }
            return CompletableFuture.supplyAsync(tuneWeave.prepareRequest(() -> tuneWeave.loadAlbumDetail(id, ignoreCache,
                            partial -> MusicEntityCache.publish(cacheGeneration, () -> progress.accept(partial)))), MusicHud.EXECUTOR)
                    .thenApply(album -> {
                        MusicEntityCache.publish(cacheGeneration, () -> {
                            MusicEntityCache.putAlbum(id, album);
                            MusicEntityCache.putAlbum(accountScope, id, album);
                        });
                        return album;
                    });
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave is unavailable"));
    }

    @Override
    public Runnable prepareQueueRefresh(Queue<QueueItem> queue) {
        return queuePublication.prepare(queue);
    }

    public long queueRevision() { return queuePublication.revision(); }

    public void refreshInitialQueue(long requestedRevision, Queue<QueueItem> queue) {
        queuePublication.publishInitialIfUnchanged(requestedRevision, queue);
    }

    @Override
    public synchronized void refreshQueue(Queue<QueueItem> queue) {
        List<QueueItem> local = new ArrayList<>(musicQueue);
        List<QueueItem> fresh = new ArrayList<>(queue);
        List<QueueItem> toRemove = new ArrayList<>();
        int i = 0, j = 0;
        while (i < local.size() && j < fresh.size()) {
            if (sameItem(local.get(i), fresh.get(j))) {
                i++;
                j++;
            } else {
                // relative order is preserved, so local[i] must have been removed
                toRemove.add(local.get(i));
                i++;
            }
        }
        while (i < local.size()) {
            toRemove.add(local.get(i++));
        }
        for (int k = toRemove.size() - 1; k >= 0; k--) {
            QueueItem removed = toRemove.get(k);
            int index = 0;
            for (QueueItem item : musicQueue) {
                if (item == removed) {
                    break;
                }
                index++;
            }
            musicQueue.remove(removed);
            int finalIndex = index;
            musicQueueRemoveListeners.forEach(l -> l.accept(finalIndex, removed));
        }
        for (; j < fresh.size(); j++) {
            QueueItem added = fresh.get(j);
            musicQueue.add(added);
            musicQueuePushListeners.forEach(l -> l.accept(added));
        }
        musicQueueRefreshListeners.forEach(l -> l.accept(queue));
    }

    private static boolean sameItem(QueueItem a, QueueItem b) {
        return a.queueUniqueID().equals(b.queueUniqueID());
    }

    @Override
    public void sendPushMusicToQueue(MusicDetail musicDetail) {
        clientNetworkService.sendToServer(new ClientPushMusicToQueueMessage(musicDetail));
    }

    @Override
    public void sendRemoveMusicFromQueue(int index, QueueItem item) {
        clientNetworkService.sendToServer(new ClientRemoveMusicFromQueueMessage(index, item.musicDetail().getId(), item.queueUniqueID()));
    }

    @Override
    public synchronized void switchMusic(PlaybackSession playbackSession,
                                         MusicDetail nextIdleMusicDetail, String message) {
        switchMusic(playbackSession, nextIdleMusicDetail, message, 0);
    }

    @Override
    public synchronized void switchMusic(PlaybackSession playbackSession,
                                         MusicDetail nextIdleMusicDetail, String message, long previewRevision) {
        if (publicPlayback.accept(playbackSession, nextIdleMusicDetail, previewRevision) && clientConfig.getEnable()
                && message != null && !message.isEmpty()) {
            MuiModApi.postToUiThread(() -> {
                var decor = UIManager.getInstance().getDecorView();
                if (decor != null) ToastUtil.show(Toast.makeText(decor.getContext(), message, Toast.LENGTH_SHORT));
            });
        }
    }

    @Override
    public void updateNextToPlay(IdlePreview preview) {
        publicPlayback.updatePreview(preview);
    }

    public CompletableFuture<indi.mopelotus.musichud.beans.music.actions.MessagedResult<Boolean>> rotateNextToPlay() {
        IdlePreview preview = publicPlayback.preview();
        return RequestResponseManager.send(new RotateNextToPlayRequest(
                        preview.sessionId(), preview.sequence(), preview.revision()),
                        RotateNextToPlayResponse.class, Duration.ofSeconds(10))
                .thenApply(RotateNextToPlayResponse::getResult)
                .exceptionally(error -> indi.mopelotus.musichud.beans.music.actions.MessagedResult.fail(
                        MusicHud.MOD_ID + ".text.rotateNextFailed", false));
    }

    private void resetPublicPlayback() {
        publicPlayback.reset();
    }

    private void publishPublicPlayback(PlaybackSession session, MusicDetail next) {
        // The server's song, lyrics and timeline remain visible during buffering or local failure.
        NowPlayingInfo.getInstance().switchMusicInfoAt(session.musicDetail(), next,
                session.isActive() ? session.startTime() : null);
        QueueItem queued = musicQueue.peek();
        MusicDetail preload = queued == null ? next : queued.musicDetail();
        if (preload != null && preload != MusicDetail.NONE) {
            ImageUtils.downloadAsync(preload.getAlbum().getThumbnailPicUrl(240));
            HudRendererManager.getInstance().preloadAlbumImage(preload.getAlbum());
        }
        if (session.isActive()) ImageUtils.downloadAsync(session.musicDetail().getAlbum().getThumbnailPicUrl(240));
    }

    @Override
    public PlaybackResolution resolvePublicPlayback(MusicDetail requestedMusic) {
        return tuneWeave.prepareRequest(() -> resolvePublicPlaybackInScope(requestedMusic)).get();
    }

    private PlaybackResolution resolvePublicPlaybackInScope(MusicDetail requestedMusic) {
        if (requestedMusic == null || requestedMusic == MusicDetail.NONE
                || requestedMusic.getSourceRef().isBlank() || !tuneWeave.isAvailable()) {
            throw new IllegalArgumentException("TuneWeave playback reference is unavailable");
        }
        MusicDetail canonical;
        if (requestedMusic.isCloudSource()) {
            canonical = tuneWeave.loadCloudTrack(requestedMusic);
        } else if ("track".equals(requestedMusic.getSourceKind())) {
            canonical = tuneWeave.loadTrackDetail(requestedMusic);
        } else if ("video".equals(requestedMusic.getSourceKind())) {
            canonical = tuneWeave.loadVideoPlaybackDetail(requestedMusic);
        } else if (Set.of("podcast_episode", "radio_station")
                .contains(requestedMusic.getSourceKind())) {
            canonical = tuneWeave.loadProgramPlaybackDetail(requestedMusic);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported TuneWeave playback kind: " + requestedMusic.getSourceKind());
        }
        if (canonical == null || canonical == MusicDetail.NONE) {
            throw new IllegalStateException("TuneWeave returned no canonical track");
        }
        canonical.setSourceRef(requestedMusic.getSourceRef());
        canonical.setSourceKind(requestedMusic.getSourceKind());
        canonical.setSourcePartRef(requestedMusic.getSourcePartRef());
        canonical.setClientHostedUni(requestedMusic.isClientHostedUni());
        canonical.setCloudSource(requestedMusic.isCloudSource());
        canonical.setPusherInfo(requestedMusic.getPusherInfo());
        canonical = canonical.withPlaybackSource(requestedMusic.getPlaybackSource());
        try {
            LyricInfo lyrics;
            if (canonical.isCloudSource()) {
                lyrics = tuneWeave.loadCloudLyrics(canonical);
            } else if ("video".equals(canonical.getSourceKind())) {
                lyrics = tuneWeave.loadVideoLyrics(canonical);
            } else {
                lyrics = tuneWeave.loadLyrics(canonical);
            }
            canonical.setLyricInfo(lyrics == null ? LyricInfo.NONE : lyrics);
        } catch (RuntimeException ignored) {
            canonical.setLyricInfo(LyricInfo.NONE);
        }
        MusicResourceInfo resource = tuneWeave.getMusicResourceInfo(
                canonical, clientConfig.getPrimaryChosenQuality());
        if (resource == null || resource == MusicResourceInfo.NONE || resource.getUrl().isBlank()) {
            throw new IllegalStateException("TuneWeave returned no playable resource");
        }
        return new PlaybackResolution(canonical, resource);
    }

    @Override
    public CompletableFuture<Artist> loadArtist(long id, boolean ignoreCache) {
        Object cacheGeneration = MusicEntityCache.captureGeneration();
        AccountScope accountScope = AccountScope.fromSession(tuneWeave.cachedSession(tuneWeave.hasArtist(id)
                ? tuneWeave.platformOfEntity(Artist.class, id) : tuneWeave.defaultPlatform()));
        if (!ignoreCache) {
            Artist scoped = MusicEntityCache.getArtist(accountScope, id);
            if (scoped != null) return CompletableFuture.completedFuture(scoped);
        }
        if (tuneWeave.isAvailable()) {
            if (!tuneWeave.hasArtist(id)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Unknown TuneWeave artist reference: " + id));
            }
            return CompletableFuture.supplyAsync(tuneWeave.prepareRequest(() -> tuneWeave.loadArtistDetail(id)), MusicHud.EXECUTOR)
                    .thenApply(artist -> {
                        MusicEntityCache.publish(cacheGeneration, () -> {
                            MusicEntityCache.putArtist(id, artist);
                            MusicEntityCache.putArtist(accountScope, id, artist);
                        });
                        return artist;
                    });
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave is unavailable"));
    }

    @Override
    public CompletableFuture<List<MusicDetail>> loadArtistMusic(long id, int offset) {
        if (tuneWeave.isAvailable()) {
            if (!tuneWeave.hasArtist(id)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Unknown TuneWeave artist reference: " + id));
            }
            return CompletableFuture.supplyAsync(tuneWeave.prepareRequest(() -> tuneWeave.loadArtistTracks(id, offset)), MusicHud.EXECUTOR);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave is unavailable"));
    }

    @Override
    public void voteForSkipCurrent() {
        if (NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail() != null) {
            clientNetworkService.sendToServer(new VoteSkipCurrentMusicMessage(NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail().getId()));
        }
    }

    @Override
    public void keyBindsVoteSkipCurrent() {
        MusicDetail currentlyPlayingMusicDetail = NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail();
        if (currentlyPlayingMusicDetail != null && currentlyPlayingMusicDetail != MusicDetail.NONE) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastPressTime <= 3000) {
                lastPressTime = 0;
                voteForSkipCurrent();
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    String s = IClientDistUtil.getInstance().inSinglePlayer()
                            ? I18n.get(MusicHud.MOD_ID + ".text.skipConfirmed")
                            : I18n.get(MusicHud.MOD_ID + ".text.voteForSkipConfirmed");
                    ToastUtil.show(Toast.makeText(context, s, Toast.LENGTH_SHORT));
                });
            } else {
                lastPressTime = currentTimeMillis;
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    String s = IClientDistUtil.getInstance().inSinglePlayer()
                            ? I18n.get(MusicHud.MOD_ID + ".text.confirmSkip")
                            : I18n.get(MusicHud.MOD_ID + ".text.confirmVoteForSkip");
                    ToastUtil.show(Toast.makeText(context, s, Toast.LENGTH_SHORT));
                });
            }
        }
    }

    @Override
    public CompletableFuture<UserCategoryPlaylists> loadUserPlaylists(boolean ignoreCache) {
        if (!LoginService.getInstance().isLogined()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot call AccountService.loadUserPlaylists when logined as anonymous"));
        }
        if (tuneWeave.hasCredential(tuneWeave.defaultPlatform())) {
            return CompletableFuture.supplyAsync(tuneWeave.prepareAccountRequest(tuneWeave.defaultPlatform(), tuneWeave::loadAccountPlaylists), MusicHud.EXECUTOR);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave account credential is unavailable"));
    }

    @Override
    public CompletableFuture<LinkedHashSet<Album>> loadUserAlbums(boolean ignoreCache) {
        if (!LoginService.getInstance().isLogined()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot call AccountService.loadUserAlbums when logined as anonymous"));
        }
        if (tuneWeave.hasCredential(tuneWeave.defaultPlatform())) {
            return CompletableFuture.supplyAsync(tuneWeave.prepareAccountRequest(tuneWeave.defaultPlatform(), tuneWeave::loadAccountAlbums), MusicHud.EXECUTOR);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave account credential is unavailable"));
    }

    @Override
    public CompletableFuture<LinkedHashSet<Artist>> loadUserArtists(boolean ignoreCache) {
        if (!LoginService.getInstance().isLogined()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot call AccountService.loadUserArtists when logined as anonymous"));
        }
        if (tuneWeave.hasCredential(tuneWeave.defaultPlatform())) {
            return CompletableFuture.supplyAsync(tuneWeave.prepareAccountRequest(tuneWeave.defaultPlatform(), tuneWeave::loadAccountArtists), MusicHud.EXECUTOR);
        }
        return CompletableFuture.failedFuture(new IllegalStateException("TuneWeave account credential is unavailable"));
    }

    @Override
    public CompletableFuture<Artist> loadArtistDetailAsync(Artist artist) {
        List<MusicDetail> musicDetails = artist.getMusicDetails();
        if (musicDetails == null || musicDetails.isEmpty()) {
            return loadArtist(artist.getId(), false);
        } else return CompletableFuture.completedFuture(artist);
    }

    @Override
    public CompletableFuture<Collection<MusicDetail>> loadMoreMusicOfArtist(Artist artist) {
        return MusicService.getInstance().loadArtistMusic(artist.getId(), artist.getMusicDetails().size())
                .thenApply(musicDetails1 -> {
                    artist.getMusicDetails().addAll(musicDetails1);
                    return musicDetails1;
                });
    }

    @Override
    public CompletableFuture<loadMusicCollectionMoreDataResult> loadMoreMusicOfCollection(MusicCollection musicCollection, boolean ignoreCache) {
        return loadMoreMusicOfCollection(musicCollection, ignoreCache, ignored -> {});
    }

    public CompletableFuture<loadMusicCollectionMoreDataResult> loadMoreMusicOfCollection(MusicCollection musicCollection,
            boolean ignoreCache, java.util.function.Consumer<MusicCollection> progress) {
        if (musicCollection instanceof Album album) {
            return loadAlbumDetail(album.getId(), ignoreCache, progress::accept)
                    .thenApply(albumInfo -> new loadMusicCollectionMoreDataResult(albumInfo, albumInfo.getMusicDetails()));
        } else if (musicCollection instanceof Playlist playlist) {
            return loadPlaylistDetail(playlist.getId(), ignoreCache, progress::accept)
                    .thenApply(playlist1 -> new loadMusicCollectionMoreDataResult(playlist1, playlist1.getTracks()));
        } else {
            return CompletableFuture.failedFuture(new IllegalStateException());
        }
    }

    @Override
    public IMusicTrackState getMusicTrackState(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail.equals(MusicDetail.NONE)) {
            return MusicTrackState.NONE;
        }
        return new MusicTrackState(musicDetail);
    }

    @Override
    public ISubscribeState<Playlist> getPlaylistSubscribeState(Playlist playlist) {
        return new PlaylistSubscribeState(playlist.getId());
    }

    @Override
    public ISubscribeState<Album> getAlbumSubscribeState(Album album) {
        return new AlbumSubscribeState(album.getId());
    }

    @Override
    public ISubscribeState<Artist> getArtistSubscribedState(Artist artist) {
        return new ArtistSubscribeState(artist.getId());
    }

    @Override
    public synchronized CompletableFuture<UserCollections> loadUserCollections(boolean ignoreCache) {
        UserCollections requestedCollections = new UserCollections();
        var playlists = loadAccountPlaylists(ignoreCache);
        var albums = loadAccountAlbums(ignoreCache);
        var artists = loadAccountArtists(ignoreCache);
        return CompletableFuture.allOf(playlists, albums, artists).thenApply(ignored -> {
            requestedCollections.setUserCategoryPlaylists(playlists.join());
            requestedCollections.setSubscribedAlbums(albums.join());
            requestedCollections.setSubscribedArtists(artists.join());
            return requestedCollections;
        });
    }

    public synchronized CompletableFuture<UserCategoryPlaylists> loadAccountPlaylists(boolean refresh) {
        return loadAccountPlaylists(refresh, ignored -> {});
    }

    public synchronized CompletableFuture<UserCategoryPlaylists> loadAccountPlaylists(boolean refresh, Consumer<UserCategoryPlaylists> progress) {
        return loadAccountPlaylists(tuneWeave.defaultPlatform(), refresh, progress);
    }

    public synchronized CompletableFuture<UserCategoryPlaylists> loadAccountPlaylists(TuneWeavePlatform platform, boolean refresh, Consumer<UserCategoryPlaylists> progress) {
        return userCollections.forPlatform(platform).playlists(refresh, publish -> tuneWeave.prepareAccountRequest(platform, () -> {
            requireAccountCredential(platform);
            return tuneWeave.loadAccountPlaylists(platform, refresh, publish);
        }), progress, MusicHud.EXECUTOR);
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Album>> loadAccountAlbums(boolean refresh) {
        return loadAccountAlbums(refresh, ignored -> {});
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Album>> loadAccountAlbums(boolean refresh, Consumer<ObservableSequencedSet<Album>> progress) {
        return loadAccountAlbums(tuneWeave.defaultPlatform(), refresh, progress);
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Album>> loadAccountAlbums(TuneWeavePlatform platform, boolean refresh, Consumer<ObservableSequencedSet<Album>> progress) {
        return userCollections.forPlatform(platform).albums(refresh, publish -> tuneWeave.prepareAccountRequest(platform, () -> {
            requireAccountCredential(platform);
            return platform == TuneWeavePlatform.BILIBILI ? new ObservableSequencedSet<>()
                    : new ObservableSequencedSet<>(tuneWeave.loadAccountAlbums(platform, refresh,
                            values -> publish.accept(new ObservableSequencedSet<>(values))));
        }), progress, MusicHud.EXECUTOR);
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Artist>> loadAccountArtists(boolean refresh) {
        return loadAccountArtists(refresh, ignored -> {});
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Artist>> loadAccountArtists(boolean refresh, Consumer<ObservableSequencedSet<Artist>> progress) {
        return loadAccountArtists(tuneWeave.defaultPlatform(), refresh, progress);
    }

    public synchronized CompletableFuture<ObservableSequencedSet<Artist>> loadAccountArtists(TuneWeavePlatform platform, boolean refresh, Consumer<ObservableSequencedSet<Artist>> progress) {
        return userCollections.forPlatform(platform).artists(refresh, publish -> tuneWeave.prepareAccountRequest(platform, () -> {
            requireAccountCredential(platform);
            return platform == TuneWeavePlatform.BILIBILI ? new ObservableSequencedSet<>()
                    : new ObservableSequencedSet<>(tuneWeave.loadAccountArtists(platform, refresh,
                            values -> publish.accept(new ObservableSequencedSet<>(values))));
        }), progress, MusicHud.EXECUTOR);
    }

    private void requireAccountCredential(TuneWeavePlatform platform) {
        if (!tuneWeave.hasCredential(platform)) {
            throw new IllegalStateException("TuneWeave account credential is unavailable");
        }
    }

    public synchronized void invalidateUserCollections() {
        disableFavoriteIntelligence();
        tuneWeave.invalidateEntityCaches();
        userCollections.invalidate();
    }

    @RegisterMark
    public static class RegisterImpl implements ClientRegister {
        @Override
        public void register() {
            LoginService.getInstance().addLoginStateListener(state -> {
                if (state != IClientLoginService.LoginState.UNLOGGED) {
                    MusicService.getInstance().getIdlePlaySourceState().local().loadFromConfig();
                }
            });
        }
    }

}
