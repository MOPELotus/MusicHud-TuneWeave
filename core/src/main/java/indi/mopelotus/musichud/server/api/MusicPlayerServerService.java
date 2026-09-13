package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.music.actions.MessagedResult;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.interfaces.ServerRegister;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.PlaybackResourceFailureMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ResolvePlaybackResultMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.*;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.GetInitialStateResponse;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.server.IdleSourceAccessPolicy;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.server.playback.PlaybackResolveCoordinator;
import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import indi.mopelotus.musichud.throwable.MusicResourceLoadingException;
import indi.mopelotus.musichud.utils.IClientDistUtil;
import lombok.*;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MusicPlayerServerService {
    private final ServerConfig serverConfig;
    private static final ServerPlayerRegistry playerRegistry = ServerPlayerRegistry.getInstance();
    private static final long DEBOUNCE_DELAY_MILLIS = 500;
    private static volatile MusicPlayerServerService instance;
    final Map<PusherInfo, Set<IdlePlaySource>> idlePlaySources = new ConcurrentHashMap<>();
    private final IMusicApiService musicApiService;
    private final CurrentVoteInfo currentVoteInfo = new CurrentVoteInfo();
    private final Logger logger = MusicHud.getLogger(MusicPlayerServerService.class);
    private final IServerNetworkService serverNetworkService;
    private final PlaybackResolveCoordinator playbackResolveCoordinator;
    private final java.util.concurrent.Executor workerExecutor;

    private MusicPlayerServerService() {
        this(ServerConfig.getInstance(), IMusicApiService.getInstance(),
                IServerNetworkService.getInstance(), MusicHud.EXECUTOR);
    }

    MusicPlayerServerService(ServerConfig config, IMusicApiService musicApi,
                             IServerNetworkService network, java.util.concurrent.Executor executor) {
        serverConfig = Objects.requireNonNull(config);
        musicApiService = Objects.requireNonNull(musicApi);
        serverNetworkService = Objects.requireNonNull(network);
        workerExecutor = Objects.requireNonNull(executor);
        playbackResolveCoordinator = new PlaybackResolveCoordinator(
                network::sendToPlayer, Duration.ofSeconds(10), playerRegistry::contains);
    }
    private final AtomicInteger debounceToken = new AtomicInteger(0);
    private final AtomicInteger pusherGeneration = new AtomicInteger(0);
    private final AtomicLong playbackSequence = new AtomicLong();
    private final Map<SessionRevision, Set<UUID>> playbackFailureReports = new ConcurrentHashMap<>();
    private final Set<SessionRevision> playbackRefreshes = ConcurrentHashMap.newKeySet();
    private volatile int runningPusherGeneration = -1;

    private Runnable createMusicPusher(int generation) {
        return new Runnable() {

        @Override
        public void run() {
            if (!continuable || generation != pusherGeneration.get()) return;
            Thread thread = Thread.currentThread();
            thread.setName("MHWorker-Music-Data-Pusher");
            pusherThread = thread;
            pusherThreadRunning = true;
            String message = "";
            while (MusicPlayerServerService.this.continuable && generation == pusherGeneration.get()) {
                MusicDetail switchedToPlay = null;
                try {
                    if (musicQueue.isEmpty()) {
                        Optional<MusicDetail> optionalMusicDetail = getRandomMusicFromIdleSources();
                        if (optionalMusicDetail.isEmpty()) {
                            break;
                        } else {
                            MusicDetail musicDetail = optionalMusicDetail.get();
                            if (preloadMusicDetail == null || preloadMusicDetail.equals(MusicDetail.NONE)) {
                                preloadMusicDetail = musicDetail;
                                Optional<MusicDetail> optionalMusicDetail1 = getRandomMusicFromIdleSources();
                                if (optionalMusicDetail1.isPresent()) {
                                    switchedToPlay = preloadMusicDetail;
                                    nextIdleMusicDetail = optionalMusicDetail1.get();
                                    preloadMusicDetail = nextIdleMusicDetail;
                                } else {
                                    switchedToPlay = musicDetail;
                                    preloadMusicDetail = MusicDetail.NONE;
                                }
                            } else {
                                switchedToPlay = preloadMusicDetail;
                                preloadMusicDetail = musicDetail;
                            }
                            PusherInfo pusherInfo = switchedToPlay.getPusherInfo();
                            if (pusherInfo != null &&
                                    !playerRegistry.contains(pusherInfo.getPlayerUUID())
                            ) {
                                continue;
                            }
                        }
                    } else {
                        switchedToPlay = musicQueue.remove().musicDetail();
                        serverNetworkService.sendToPlayers(playerRegistry.players(),
                                new RefreshMusicQueueMessage(musicQueue));
                    }

                    nextIdleMusicDetail = preloadMusicDetail != null ? preloadMusicDetail : MusicDetail.NONE;

                    MusicDetail requestedMusic = switchedToPlay;
                    UUID ownerId = requestedMusic.getPusherInfo().getPlayerUUID();
                    if (requestedMusic.isCloudSource() && !playerRegistry.contains(ownerId)) {
                        serverNetworkService.sendToPlayers(playerRegistry.players(),
                                new CommonNotificationMessage(MessagedResult.fail(
                                        MusicHud.MOD_ID + ".text.resourceOwnerUnavailable", null)));
                        logger.info("Skipped owner-scoped cloud resource because owner {} is offline",
                                ownerId);
                        continue;
                    }
                    PlaybackSession playbackSession = resolvePlaybackSession(
                            UUID.randomUUID(), playbackSequence.incrementAndGet(),
                            0, requestedMusic, null,
                            () -> continuable && generation == pusherGeneration.get())
                            .orElseThrow(() -> new MusicResourceLoadingException(
                                    new IllegalStateException("No client could resolve public playback"),
                                    requestedMusic, false));
                    if (!continuable || generation != pusherGeneration.get()) {
                        break;
                    }
                    switchedToPlay = playbackSession.musicDetail();
                    synchronized (MusicPlayerServerService.this) {
                        if (!continuable || generation != pusherGeneration.get()) break;
                        currentVoteInfo.resetTo(switchedToPlay);
                        haveSentMusic = true;
                        currentPlaybackSession = playbackSession;
                        playbackResolveCoordinator.cancelStaleRequests();
                        playbackFailureReports.clear();
                        serverNetworkService.sendToPlayers(
                                playerRegistry.players(),
                                new SwitchMusicMessage(playbackSession, nextIdleMusicDetail, message));
                    }
                    message = "";
                    logger.info("Switched to music: {} (ID: {})", switchedToPlay.getName(), switchedToPlay.getId());
                    int musicIntervalMillis = 1000;
                    //noinspection BusyWait
                    Thread.sleep(switchedToPlay.getDurationMillis() + musicIntervalMillis);
                } catch (InterruptedException ignored) {//When force switch
                    if (generation != pusherGeneration.get() || !continuable) break;
                    logger.info("Skip current, switch to nextIdle");
                    if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT
                            && !IClientDistUtil.getInstance().inSinglePlayer()) {
                        message = MusicHud.MOD_ID + ".text.votePassed";
                    }
                } catch (Exception e) {
                    if (!continuable || generation != pusherGeneration.get()) break;
                    String message1;
                    if (e instanceof MusicResourceLoadingException e1 && e1.isUsingSubstitute()) {
                        message1 = MusicHud.MOD_ID + ".text.substituteMusicPushError";
                    } else {
                        message1 = MusicHud.MOD_ID + ".text.musicPushError";
                    }
                    serverNetworkService.sendToPlayers(
                            playerRegistry.players(),
                            new CommonNotificationMessage(MessagedResult.fail(message1, null))
                    );
                    logger.error("Failed to push music: {} (id: {})",
                            switchedToPlay != null ? switchedToPlay.getName() : "null",
                            switchedToPlay != null ? switchedToPlay.getId() : "-1",
                            e);
                    try {
                        //noinspection BusyWait
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            logger.info("Music Pusher stopped");
            if (pusherThread == thread) {
                pusherThread = null;
                pusherThreadRunning = false;
            }
            if (generation == pusherGeneration.get()) {
                MusicPlayerServerService.this.stopSendingMusic();
            }
        }

        private Optional<MusicDetail> getRandomMusicFromIdleSources() {
            if (idlePlaySources.isEmpty()) {
                return Optional.empty();
            }

            List<Map.Entry<PusherInfo, Set<IdlePlaySource>>> entryList =
                    new ArrayList<>(idlePlaySources.entrySet());

            if (entryList.isEmpty()) {
                return Optional.empty();
            }

            Map.Entry<PusherInfo, Set<IdlePlaySource>> randomEntry =
                    entryList.get(MusicHud.RANDOM.nextInt(entryList.size()));

            PusherInfo pusherInfo = randomEntry.getKey();
            Set<IdlePlaySource> idlePlaySource = randomEntry.getValue();

            List<IdlePlaySource> availableSources = idlePlaySource.stream()
                    .filter(playSource -> !playSource.getMusicCollection().getMusicDetails().isEmpty())
                    .toList();

            if (availableSources.isEmpty()) {
                return Optional.empty();
            }

            IdlePlaySource selectedSource = availableSources.get(MusicHud.RANDOM.nextInt(availableSources.size()));
            MusicDetail randomTrack = selectedSource.nextTrack(MusicHud.RANDOM).orElseThrow()
                    .withPlaybackSource(PlaybackSource.from(selectedSource.getMusicCollection(), selectedSource.getMode().name()));

            if (playerRegistry.contains(pusherInfo.getPlayerUUID())) {
                randomTrack.setPusherInfo(pusherInfo);
            } else {
                randomTrack.setPusherInfo(PusherInfo.EMPTY);
            }
            return Optional.of(randomTrack);
        }
        };
    }
    @Getter
    Queue<QueueItem> musicQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    boolean continuable;
    @Getter
    private volatile PlaybackSession currentPlaybackSession = PlaybackSession.NONE;
    @Getter
    private MusicDetail nextIdleMusicDetail = MusicDetail.NONE;
    private MusicDetail preloadMusicDetail = MusicDetail.NONE;
    private volatile Thread pusherThread;
    private volatile boolean pusherThreadRunning = false;
    private boolean haveSentMusic = false;

    public static MusicPlayerServerService getInstance() {
        if (instance == null) {
            synchronized (MusicPlayerServerService.class) {
                if (instance == null) {
                    instance = new MusicPlayerServerService();
                }
            }
        }
        return instance;
    }

    private void updateContinuable(boolean continuable) {
        if (continuable) {
            this.continuable = true;
            startMusicPusher();
        } else {
            // A client/world disconnect must wake a pusher that is sleeping
            // for the previous track, otherwise the next world inherits a
            // live-but-stale thread and queued requests appear to do nothing.
            stopSendingMusic();
        }
    }

    private void startMusicPusher() {
        synchronized (MusicPlayerServerService.class) {
            if (pusherThreadRunning && runningPusherGeneration == pusherGeneration.get()) {
                return;
            }
            int generation = pusherGeneration.incrementAndGet();
            runningPusherGeneration = generation;
            pusherThreadRunning = true;
            workerExecutor.execute(createMusicPusher(generation));
        }
    }

    private synchronized void stopSendingMusic() {
        pusherGeneration.incrementAndGet();
        this.continuable = false;
        if (pusherThread != null) {
            pusherThread.interrupt();
        }
        PlaybackSession stoppedSession = PlaybackSession.stopped(playbackSequence.incrementAndGet());
        if (haveSentMusic) {
            haveSentMusic = false;
            serverNetworkService.sendToPlayers(
                    playerRegistry.players(),
                    new SwitchMusicMessage(stoppedSession, MusicDetail.NONE, "")
            );
            currentVoteInfo.resetTo(MusicDetail.NONE);
        }
        currentPlaybackSession = stoppedSession;
        playbackResolveCoordinator.cancelStaleRequests();
        playbackFailureReports.clear();
        playbackRefreshes.clear();
    }

    public void sendSyncPlayingStatusToPlayer(IPlayerClient player) {
        serverNetworkService.sendToPlayer(player,
                new RefreshMusicQueueMessage(musicQueue));
        sendUpdateAllIdlePlaySourcesMessageTo(Collections.singleton(player));
        if (currentPlaybackSession.isActive()) {
            haveSentMusic = true;
            serverNetworkService.sendToPlayer(player,
                    new SyncCurrentPlayingMessage(currentPlaybackSession, nextIdleMusicDetail));
        }
    }

    public void sendUpdateAllIdlePlaySourcesMessageTo(Collection<IPlayerClient> players) {
        for (IPlayerClient player : players) {
            IdleSourcesData idleSourcesData = buildIdleSourcesData(player.getUUID());
            serverNetworkService.sendToPlayer(player,
                    new UpdateAllIdlePlaySourcesMessage(idleSourcesData.playlistSources(), idleSourcesData.albumSources()));
        }
    }

    private IdleSourcesData buildIdleSourcesData(UUID viewerId) {
        List<Playlist> publicPlaylists = new ArrayList<>();
        List<Playlist> privatePlaylists = new ArrayList<>();
        List<Album> albums = new ArrayList<>();
        for (IdlePlaySource playSource : idlePlaySources.values().stream().flatMap(Collection::stream).toList()) {
            MusicCollection musicCollection = playSource.getMusicCollection();
            PusherInfo pusherInfo = playSource.getPusherInfo();
            if (musicCollection instanceof Playlist playlist) {
                if (playlist.getPrivacy() == Privacy.PUBLIC) {
                    publicPlaylists.add(playlist.copyWithPusherInfo(pusherInfo));
                } else {
                    privatePlaylists.add(playlist.copyWithPusherInfo(pusherInfo));
                }
            } else if (musicCollection instanceof Album album) {
                albums.add(album.copyWithPusherInfo(pusherInfo));
            } else {
                throw new IllegalArgumentException("Invalid music collection type");
            }
        }

        List<Playlist> processedPrivatePlaylists = new ArrayList<>();
        for (Playlist playlist : privatePlaylists) {
            PusherInfo pusherInfo = playlist.getPusherInfo();
            if (IdleSourceAccessPolicy.canViewPrivateSource(pusherInfo, viewerId)) {
                processedPrivatePlaylists.add(playlist);
            } else {
                processedPrivatePlaylists.add(playlist.copyWithSensitiveErased());
            }
        }
        processedPrivatePlaylists.addAll(publicPlaylists);
        return new IdleSourcesData(processedPrivatePlaylists, albums);
    }

    public GetInitialStateResponse buildInitialStateFor(IPlayerClient player) {
        IdleSourcesData idleSourcesData = buildIdleSourcesData(player.getUUID());
        return new GetInitialStateResponse(
                currentPlaybackSession,
                nextIdleMusicDetail,
                new ArrayDeque<>(musicQueue),
                idleSourcesData.playlistSources(),
                idleSourcesData.albumSources()
        );
    }

    private void debouncedUpdateAllIdlePlaySources() {
        final int token = debounceToken.incrementAndGet();
        workerExecutor.execute(() -> {
            try {
                Thread.sleep(DEBOUNCE_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (debounceToken.get() == token) {
                sendUpdateAllIdlePlaySourcesMessageTo(playerRegistry.players());
            }
        });
    }

    public void pushMusicToQueue(MusicDetail musicDetail, PusherInfo pusherInfo) {
        Objects.requireNonNull(musicDetail, "musicDetail");
        String reference = musicDetail.getSourceRef();
        if (reference.isBlank() || reference.length() > 512 || !reference.contains(":")) {
            throw new IllegalArgumentException("Invalid TuneWeave resource reference");
        }
        if (!Set.of("track", "video", "podcast_episode", "radio_station")
                .contains(musicDetail.getSourceKind())) {
            throw new IllegalArgumentException("Unsupported TuneWeave queue resource kind");
        }
        if (musicDetail.getName().isBlank() || musicDetail.getName().length() > 500
                || musicDetail.getDurationMillis() <= 0
                || musicDetail.getDurationMillis() > 24 * 60 * 60 * 1000) {
            throw new IllegalArgumentException("Invalid TuneWeave queue resource metadata");
        }
        musicDetail.setPusherInfo(pusherInfo);
        musicQueue.add(new QueueItem(musicDetail, UUID.randomUUID()));
        serverNetworkService.sendToPlayers(playerRegistry.players(),
                new RefreshMusicQueueMessage(musicQueue));
        updateContinuable(true);
    }

    public void removeMusicDetailFromQueue(int index, long id, UUID queueUniqueID, UUID playerUUID) {
        for (QueueItem queueItem : musicQueue) {
            if (queueItem.musicDetail().getId() == id && queueItem.queueUniqueID().equals(queueUniqueID)) {
                if (queueItem.musicDetail().getPusherInfo().getPlayerUUID().equals(playerUUID)) {
                    musicQueue.remove(queueItem);
                    serverNetworkService.sendToPlayers(playerRegistry.players(),
                            new RefreshMusicQueueMessage(musicQueue));
                } else {
                    logger.warn("Player {} tried to remove music {} (id: {}) not pushed by them", playerUUID, queueItem.musicDetail().getName(), id);
                }
                return;
            }
        }
        logger.warn("Failed to remove music from queue: id {} with queue unique id {} not found", id, queueUniqueID);
    }

    public void addIdlePlaySource(MusicCollection submitted, PusherInfo pusherInfo) {
        addIdlePlaySource(submitted, indi.mopelotus.musichud.beans.api.IdlePlayMode.RANDOM, pusherInfo);
    }

    public void addIdlePlaySource(MusicCollection submitted, indi.mopelotus.musichud.beans.api.IdlePlayMode mode, PusherInfo pusherInfo) {
        MusicCollection collection = indi.mopelotus.musichud.server.playback.IdleSourceSnapshotValidator.copy(submitted, pusherInfo);
        IdlePlaySource idlePlaySource = new IdlePlaySource(collection.getId(), collection.getClass(), mode);
        idlePlaySource.setPusherInfo(pusherInfo);
        idlePlaySource.useClientCollection(collection);
        idlePlaySources.compute(pusherInfo, (owner, previous) -> {
            Set<IdlePlaySource> updated = previous == null ? new HashSet<>() : new HashSet<>(previous);
            if (!updated.contains(idlePlaySource) && updated.size() >= 50) throw new IllegalArgumentException("Too many idle sources");
            updated.remove(idlePlaySource);
            updated.add(idlePlaySource);
            return Set.copyOf(updated);
        });
        updateContinuable(true);
        debouncedUpdateAllIdlePlaySources();
    }

    public void removeIdlePlaySource(long id, Class<?> musicCollectionClass, PusherInfo pusherInfo) {
        idlePlaySources.computeIfPresent(pusherInfo, (owner, previous) -> {
            Set<IdlePlaySource> musicCollections = new HashSet<>(previous);
            IdlePlaySource idlePlaySource = new IdlePlaySource(id, musicCollectionClass);
            idlePlaySource.setPusherInfo(pusherInfo);
            musicCollections.remove(idlePlaySource);
            return musicCollections.isEmpty() ? null : Set.copyOf(musicCollections);
        });
        debouncedUpdateAllIdlePlaySources();
    }

    public void voteSkipCurrent(long id, UUID playerUUID) {
        currentVoteInfo.vote(id, playerUUID);
    }

    public void acceptPlaybackResolution(IPlayerClient resolver, ResolvePlaybackResultMessage result) {
        playbackResolveCoordinator.accept(resolver, result);
    }

    public void reportPlaybackResourceFailure(IPlayerClient player,
                                              PlaybackResourceFailureMessage report) {
        PlaybackSession current = currentPlaybackSession;
        if (!current.isActive() || !current.sessionId().equals(report.sessionId())
                || current.revision() != report.revision()
                || !playerRegistry.contains(player.getUUID())) {
            return;
        }
        SessionRevision key = new SessionRevision(report.sessionId(), report.revision());
        Set<UUID> reporters = playbackFailureReports.computeIfAbsent(
                key, ignored -> ConcurrentHashMap.newKeySet());
        reporters.add(player.getUUID());
        int onlinePlayers = Math.max(1, playerRegistry.players().size());
        int threshold = Math.min(2, onlinePlayers);
        if (reporters.size() < threshold || !playbackRefreshes.add(key)) {
            return;
        }
        workerExecutor.execute(() -> refreshPlaybackSession(current, key));
    }

    private void refreshPlaybackSession(PlaybackSession staleSession, SessionRevision failureKey) {
        try {
            Optional<PlaybackSession> refreshed = resolvePlaybackSession(
                    staleSession.sessionId(), staleSession.sequence(),
                    staleSession.revision() + 1,
                    staleSession.musicDetail(), staleSession.startTime(),
                    () -> isCurrentSession(staleSession));
            if (refreshed.isEmpty()) return;
            synchronized (this) {
                if (!isCurrentSession(staleSession)) {
                    return;
                }
                currentPlaybackSession = refreshed.get();
                playbackResolveCoordinator.cancelStaleRequests();
                serverNetworkService.sendToPlayers(
                        playerRegistry.players(),
                        new SwitchMusicMessage(refreshed.get(), nextIdleMusicDetail, ""));
            }
        } finally {
            playbackFailureReports.remove(failureKey);
            playbackRefreshes.remove(failureKey);
        }
    }

    private boolean isCurrentSession(PlaybackSession expected) {
        PlaybackSession current = currentPlaybackSession;
        return current.isActive() && current.sessionId().equals(expected.sessionId())
                && current.sequence() == expected.sequence() && current.revision() == expected.revision();
    }

    private Optional<PlaybackSession> resolvePlaybackSession(UUID sessionId, long sequence,
                                                             int revision,
                                                             MusicDetail requested,
                                                             ZonedDateTime startTime,
                                                             java.util.function.BooleanSupplier stillCurrent) {
        if (!stillCurrent.getAsBoolean()) return Optional.empty();
        List<IPlayerClient> onlinePlayers = playerRegistry.players();
        List<IPlayerClient> candidates = PlaybackResolveCoordinator.eligibleResolvers(
                onlinePlayers, requested);
        for (IPlayerClient candidate : candidates) {
            if (!stillCurrent.getAsBoolean()) return Optional.empty();
            Optional<PlaybackResolution> resolution = playbackResolveCoordinator.resolveWith(
                    candidate, requested, revision, stillCurrent);
            if (resolution.isEmpty()) continue;
            try {
                MusicDetail canonical = resolution.get().musicDetail();
                canonical.setPusherInfo(requested.getPusherInfo());
                ZonedDateTime authoritativeStartTime = startTime == null
                        ? ZonedDateTime.now() : startTime;
                return Optional.of(SharedResourceValidator.createSession(
                        sessionId, sequence, revision, requested, canonical,
                        resolution.get().resourceInfo(), authoritativeStartTime));
            } catch (IllegalArgumentException error) {
                logger.warn("Rejected invalid public playback resolution from player {}",
                        candidate.getUUID());
            }
        }
        return Optional.empty();
    }

    public void removeAllIdlePlaySource(PusherInfo pusherInfo) {
        idlePlaySources.remove(pusherInfo);
        debouncedUpdateAllIdlePlaySources();
    }

    public void reset() {
        debounceToken.incrementAndGet();
        musicQueue.clear();
        idlePlaySources.clear();
        stopSendingMusic();
        haveSentMusic = false;
        nextIdleMusicDetail = MusicDetail.NONE;
        preloadMusicDetail = MusicDetail.NONE;
    }

    private record IdleSourcesData(List<Playlist> playlistSources, List<Album> albumSources) {
    }

    private record SessionRevision(UUID sessionId, int revision) {
    }

    @RegisterMark
    public static class Register implements ServerRegister {
        @Override
        public void register() {
            playerRegistry.addListener((set) -> {
                if (instance != null) {
                    instance.playbackResolveCoordinator.cancelUnavailableResolvers();
                    Set<UUID> onlinePlayerIds = new HashSet<>();
                    set.forEach(player -> onlinePlayerIds.add(player.getUUID()));
                    boolean removedSources = instance.idlePlaySources.keySet()
                            .removeIf(pusher -> !onlinePlayerIds.contains(pusher.getPlayerUUID()));
                    if (removedSources) {
                        instance.debouncedUpdateAllIdlePlaySources();
                    }
                    instance.updateContinuable(!set.isEmpty());
                }
            });
        }
    }

    private boolean unpublishedIntegratedWorld() {
        return MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT
                && IClientDistUtil.getInstance().inSinglePlayer();
    }

    @Getter
    @Setter
    private class CurrentVoteInfo {
        final Set<UUID> votedPlayers = new HashSet<>();
        MusicDetail musicDetail;
        float voteRate;

        public synchronized void vote(long id, UUID playerUUID) {
            // Unpublished integrated-server worlds skip immediately. A published LAN world
            // still runs inside the host client process, so Side.CLIENT must not bypass voting.
            if (unpublishedIntegratedWorld()) {
                if (pusherThread != null) pusherThread.interrupt();
                logger.info("Skip current music in unpublished singleplayer");
                return;
            }
            if (musicDetail == null || musicDetail == MusicDetail.NONE || musicDetail.getId() != id) {
                return;
            }
            if (!votedPlayers.add(playerUUID)) {
                return;
            }
            voteRate += 1.0f / Math.max(1, playerRegistry.players().size());
            if (!PusherInfo.EMPTY.getPlayerUUID().equals(musicDetail.getPusherInfo().getPlayerUUID())
                    && musicDetail.getPusherInfo().getPlayerUUID().equals(playerUUID)) {
                voteRate += (float) serverConfig.getPusherVoteAdditionalRate();
                logger.info("Pusher player \"{}\" voted for skip current music {}:{}", playerUUID, id, musicDetail.getName());
            } else {
                logger.info("Player \"{}\" voted for skip current music {}:{}", playerUUID, id, musicDetail.getName());
            }
            voteRate = Math.clamp(voteRate, 0.0f, 1.0f);
            if (voteRate >= 0.5f) {
                logger.info("Try to skip current music as voting rate reach: {} >= 0.5", voteRate);
                if (pusherThread != null) {
                    pusherThread.interrupt();
                }
                resetTo(MusicDetail.NONE);
            }
        }
        public synchronized void resetTo(MusicDetail musicDetail) {
            this.musicDetail = musicDetail;
            voteRate = 0;
            votedPlayers.clear();
        }
    }
}
