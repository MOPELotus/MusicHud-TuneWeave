package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.client.utils.PlayerInfoUtil;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.lyrics.FullLineLyricParser;
import indi.mopelotus.musichud.client.utils.lyrics.WordByWordLyricParser;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.Unregister;
import io.github.selemba1000.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NowPlayingInfo {
    private static volatile NowPlayingInfo instance = null;
    private final Logger logger = MusicHud.getLogger(NowPlayingInfo.class);
    @Getter
    private final Set<Consumer<LyricLine>> lyricLineUpdateListener = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<BiConsumer<MusicDetail, MusicDetail>> musicSwitchListener = ConcurrentHashMap.newKeySet();
    private final Set<Consumer<PlaybackSnapshot>> playbackStateListeners = ConcurrentHashMap.newKeySet();
    private final Object playbackStateLock = new Object();
    private volatile PlaybackSnapshot playbackSnapshot =
            new PlaybackSnapshot(null, null, List.of(), null, null);
    private final ClientConfig clientConfig = ClientConfig.getInstance();
    private volatile JMTC jmtc;
    @Setter
    @Getter
    private Duration updateInAdvanceDuration = Duration.of(500, ChronoUnit.MILLIS);
    private MusicDetail smtcPlayingMusicDetail;
    private final ScheduledExecutorService mediaExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MH-JMTC"); thread.setDaemon(true); return thread;
    });
    private final LatestMediaArtwork mediaArtwork = new LatestMediaArtwork(mediaExecutor);
    private long lyricGeneration;
    @Getter
    private volatile MusicDetail currentlyPlayingMusicDetail;
    private volatile MusicDetail nextToPlayIdleMusicDetail;
    @Getter
    private volatile Duration musicDuration = null;
    @Getter
    private volatile ZonedDateTime musicStartTime = null;
    private volatile ArrayDeque<LyricLine> lyricLines;
    @Getter
    private LyricLine currentLyricLine;
    private Thread lyricUpdaterVThread;

    private NowPlayingInfo() {
        mediaExecutor.execute(() -> {
            try { initJmtc(); }
            catch (Throwable error) { logger.warn("System media controls unavailable ({})", error.getClass().getSimpleName()); }
        });
        mediaExecutor.scheduleAtFixedRate(this::updateMediaState, 1, 1, TimeUnit.SECONDS);
    }

    public static NowPlayingInfo getInstance() {
        if (instance == null) {
            synchronized (NowPlayingInfo.class) {
                if (instance == null) {
                    instance = new NowPlayingInfo();
                }
            }
        }
        return instance;
    }

    private void initJmtc() {
        jmtc = JMTC.getInstance(new JMTCSettings("Minecraft-MusicHud TuneWeave", MusicHud.DISPLAY_NAME));
        JMTCCallbacks callbacks = new JMTCCallbacks();
        callbacks.onPlay = () -> changeMediaMute(false);
        callbacks.onPause = () -> changeMediaMute(true);
        callbacks.onNext = () -> MusicHud.EXECUTOR.execute(() -> MusicService.getInstance().voteForSkipCurrent());
        callbacks.onVolume = volume -> {
            if (volume == null || !Double.isFinite(volume.doubleValue())) return;
            clientConfig.forceSetSoundVolume((int) Math.round(Math.clamp(volume.doubleValue(), 0, 1) * 100));
            clientConfig.save();
            indi.mopelotus.musichud.client.ui.screen.MainFragment.refreshCoverScale();
            mediaExecutor.execute(this::updateMediaState);
        };
        jmtc.setEnabled(true);
        jmtc.setEnabledButtons(new JMTCEnabledButtons(true, true, false, true, false));
        jmtc.setCallbacks(callbacks);
        jmtc.setMediaType(JMTCMediaType.Music);
        updateMediaState();
    }

    private void changeMediaMute(boolean muted) {
        MusicHud.EXECUTOR.execute(() -> {
            clientConfig.setMuted(muted);
            clientConfig.save();
            indi.mopelotus.musichud.client.ui.screen.MainFragment.refreshCoverScale();
            mediaExecutor.execute(this::updateMediaState);
        });
    }

    /** Serialized native media updates; artwork downloads never block this executor. */
    private void updateMediaState() {
        if (jmtc == null) return;
        try {
            PlaybackSnapshot state = snapshot();
            MusicDetail music = state.musicDetail();
            if (music != smtcPlayingMusicDetail) {
                smtcPlayingMusicDetail = music;
                mediaArtwork.reset();
                if (music != null && music != MusicDetail.NONE) {
                    String artists = music.getArtists().stream().map(Artist::getName)
                            .reduce((a, b) -> a + " / " + b).orElse("");
                    String name = music.getName(), album = music.getAlbum().getName();
                    List<MusicDetail> albumTracks = new ArrayList<>(music.getAlbum().getMusicDetails());
                    int count = albumTracks.size(), index = albumTracks.indexOf(music);
                    long duration = Math.max(0, music.getDurationMillis());
                    jmtc.setTimelineProperties(new JMTCTimelineProperties(0L, duration, 0L, duration));
                    jmtc.setMediaProperties(new JMTCMusicProperties(name, artists, album, artists,
                            new String[]{""}, count, index, null));
                    mediaArtwork.load(() -> loadMediaArtwork(music.getAlbum().getPicUrl()),
                            () -> snapshot().musicDetail() == music, path -> {
                                try {
                                    jmtc.setMediaProperties(new JMTCMusicProperties(name, artists, album, artists,
                                            new String[]{""}, count, index, path.toUri()));
                                    jmtc.updateDisplay();
                                } catch (RuntimeException error) { logger.debug("System media artwork update failed"); }
                            });
                }
            }
            long position = state.startedAt() == null ? 0
                    : Math.max(0, Duration.between(state.startedAt(), ZonedDateTime.now()).toMillis());
            long duration = state.duration() == null ? 0 : Math.max(0, state.duration().toMillis());
            jmtc.setPosition(Math.min(position, duration));
            jmtc.setPlayingState(music == null || music == MusicDetail.NONE ? JMTCPlayingState.CLOSED
                    : state.startedAt() == null || position >= duration ? JMTCPlayingState.STOPPED
                    : clientConfig.getMuted() ? JMTCPlayingState.PAUSED : JMTCPlayingState.PLAYING);
            jmtc.setParameters(new JMTCParameters(JMTCParameters.LoopStatus.Track,
                    clientConfig.getMuted() ? 0 : clientConfig.getSoundVolume() / 100.0, 1.0, false));
            jmtc.updateDisplay();
        } catch (RuntimeException error) { logger.debug("System media state update failed ({})", error.getClass().getSimpleName()); }
    }

    private CompletableFuture<Path> loadMediaArtwork(String url) {
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            return ImageUtils.downloadAsync(url, stream -> {
                try { return copyMediaArtwork(stream); }
                catch (IOException error) { throw new CompletionException(error); }
            }, false);
        }
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stream = getClass().getResourceAsStream("/assets/musichud_tuneweave/icon.png")) {
                if (stream == null) return null;
                return copyMediaArtwork(stream);
            } catch (IOException error) { throw new CompletionException(error); }
        }, MusicHud.EXECUTOR);
    }

    private static Path copyMediaArtwork(InputStream stream) throws IOException {
        Path file = Files.createTempFile("musichud-tuneweave-media-", ".png");
        try {
            Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(file);
            throw error;
        }
    }

    private Duration getCallTime(LyricLine line) {
        return line.getStartTime().minus(updateInAdvanceDuration);
    }

    public float getProgressRate() {
        if (musicDuration == null || musicStartTime == null) {
            return 0.0f;
        }
        long durationMillis = musicDuration.toMillis();
        if (durationMillis <= 0L) {
            return 0.0f;
        }
        float progress = (float) Duration.between(musicStartTime, ZonedDateTime.now()).toMillis() / durationMillis;
        return Math.max(0.0f, Math.min(1.0f, progress));
    }

    public PlaybackSnapshot snapshot() {
        return playbackSnapshot;
    }

    public Unregister addPlaybackStateListener(Consumer<PlaybackSnapshot> listener) {
        playbackStateListeners.add(Objects.requireNonNull(listener));
        return () -> playbackStateListeners.remove(listener);
    }

    public void switchMusicInfo(MusicDetail musicDetail, MusicDetail idleNextToPlay) {
        switchMusicInfoAt(musicDetail, idleNextToPlay, null);
    }

    /** Publish one complete authoritative snapshot, including its timeline, before local audio starts. */
    public void switchMusicInfoAt(MusicDetail musicDetail, MusicDetail idleNextToPlay, ZonedDateTime startTime) {
        MusicDetail previous;
        synchronized (playbackStateLock) {
            cancelLyricsUpdaterLocked();
            previous = currentlyPlayingMusicDetail;
            currentlyPlayingMusicDetail = musicDetail;
            currentLyricLine = null;
            nextToPlayIdleMusicDetail = idleNextToPlay;
            musicDuration = musicDetail.equals(MusicDetail.NONE)
                    ? null : Duration.ofMillis(musicDetail.getDurationMillis());
            musicStartTime = startTime;
            parseLyrics(musicDetail);
            publishPlaybackStateLocked();
        }
        try {
            HudRendererManager.getInstance().switchMusic(musicDetail);
        } catch (RuntimeException error) {
            logger.warn("HUD renderer failed to switch music", error);
        }
        callMusicSwitchListeners(previous, musicDetail);
        callLyricsUpdateListeners(null);
        if (startTime != null) startLyricsUpdater();
    }

    /** Publish through the same snapshot subscription without disturbing the current lyrics or timeline. */
    public void updateNextToPlayIdle(MusicDetail next) {
        synchronized (playbackStateLock) {
            nextToPlayIdleMusicDetail = java.util.Objects.requireNonNull(next);
            publishPlaybackStateLocked();
        }
    }

    private void parseLyrics(MusicDetail musicDetail) {
        LyricInfo lyricInfo = musicDetail.getLyricInfo();
        if (lyricInfo.equals(LyricInfo.NONE)) {
            this.lyricLines = null;
            return;
        }
        try {
            ArrayDeque<LyricLine> parsed = lyricInfo.withWordByWordLyric()
                    ? WordByWordLyricParser.parse(musicDetail)
                    : FullLineLyricParser.parse(musicDetail);
            this.lyricLines = parsed;
        } catch (Exception e) {
            this.lyricLines = null;
            logger.warn("Failed to load lyrics of music: {} (id:{}), exception: {}: {}",
                    musicDetail.getName(), musicDetail.getId(), e.getClass().getName(), e.getMessage());
        }
    }

    public void startAt(MusicDetail expectedMusic, ZonedDateTime zonedDateTime) {
        synchronized (playbackStateLock) {
            if (expectedMusic == null || expectedMusic == MusicDetail.NONE
                    || currentlyPlayingMusicDetail != expectedMusic) {
                return;
            }
            musicStartTime = Objects.requireNonNullElseGet(zonedDateTime, ZonedDateTime::now);
            publishPlaybackStateLocked();
        }
        startLyricsUpdater();
    }

    private void cancelLyricsUpdaterLocked() {
        lyricGeneration++;
        if (lyricUpdaterVThread != null) lyricUpdaterVThread.interrupt();
        lyricUpdaterVThread = null;
    }

    private void startLyricsUpdater() {
        synchronized (playbackStateLock) {
            cancelLyricsUpdaterLocked();
            PlaybackSnapshot state = snapshot();
            if (state.startedAt() == null || state.lyrics().isEmpty()) return;
            long generation = lyricGeneration;
            MusicHud.EXECUTOR.execute(() -> runLyricsUpdater(state, generation));
        }
    }

    private void runLyricsUpdater(PlaybackSnapshot state, long generation) {
        Thread thread = Thread.currentThread();
        synchronized (playbackStateLock) {
            if (generation != lyricGeneration) return;
            lyricUpdaterVThread = thread;
        }
        try {
            List<LyricLine> lines = state.lyrics();
            for (int index = 0; index < lines.size(); index++) {
                LyricLine line = lines.get(index);
                if (line.getStartTime() == null) continue;
                if (sleepUntil(state.startedAt(), getCallTime(line))) return;
                synchronized (playbackStateLock) {
                    if (generation != lyricGeneration) return;
                    // Skip elapsed lines when seeking/recovering, then emit the current line once.
                    if (index + 1 < lines.size() && lines.get(index + 1).getStartTime() != null
                            && !ZonedDateTime.now().isBefore(state.startedAt().plus(getCallTime(lines.get(index + 1))))) continue;
                    currentLyricLine = line;
                    callLyricsUpdateListeners(line);
                }
            }
        } finally {
            synchronized (playbackStateLock) {
                if (lyricUpdaterVThread == thread) lyricUpdaterVThread = null;
            }
        }
    }

    private void callMusicSwitchListeners(MusicDetail previous, MusicDetail current) {
        ListenerDispatcher.dispatch(musicSwitchListener,
                listener -> listener.accept(previous, current),
                error -> logger.warn("Music switch listener failed", error));
    }

    private void callLyricsUpdateListeners(LyricLine line) {
        ListenerDispatcher.dispatch(lyricLineUpdateListener,
                listener -> listener.accept(line),
                error -> logger.warn("Lyrics listener failed", error));
    }

    private void publishPlaybackStateLocked() {
        ArrayDeque<LyricLine> currentLyrics = lyricLines;
        PlaybackSnapshot published = new PlaybackSnapshot(
                currentlyPlayingMusicDetail, nextToPlayIdleMusicDetail,
                currentLyrics == null ? List.of() : List.copyOf(currentLyrics),
                musicDuration, musicStartTime);
        playbackSnapshot = published;
        mediaExecutor.execute(this::updateMediaState);
        ListenerDispatcher.dispatch(playbackStateListeners,
                listener -> listener.accept(published),
                error -> logger.warn("Playback state listener failed", error));
    }

    private boolean sleepUntil(ZonedDateTime musicStartTime, Duration startTime) {
        Duration between = Duration.between(ZonedDateTime.now(), musicStartTime.plus(startTime));
        if (between.isPositive()) {
            try {
                Thread.sleep(between);
            } catch (InterruptedException ignored) {
                return true;
            }
        }
        return false;
    }

    public Duration getPlayedDuration() {
        if (musicStartTime == null) {
            return Duration.ZERO;
        }
        Duration startedPlayingDuration = Duration.between(musicStartTime, ZonedDateTime.now());
        if (startedPlayingDuration.isNegative()) {
            return Duration.ZERO;
        }
        if (musicDuration != null && startedPlayingDuration.compareTo(musicDuration) > 0) {
            return musicDuration;
        } else {
            return startedPlayingDuration;
        }
    }

    public boolean isCompleted() {
        if (musicStartTime == null || musicDuration == null) {
            return false;
        }
        Duration startedPlayingDuration = Duration.between(musicStartTime, ZonedDateTime.now());
        return startedPlayingDuration.compareTo(musicDuration) > 0;
    }

    public PlayerInfo getPusherPlayerInfo() {
        if (currentlyPlayingMusicDetail != null) {
            return PlayerInfoUtil.getPlayerInfoByUUID(currentlyPlayingMusicDetail.getPusherInfo().getPlayerUUID());//Mainly just a Map.get call, no need to cache
        } else {
            return null;
        }
    }

    public MusicDetail getNextToPlayIdleMusicDetail() {
        var next = MusicService.getInstance().getMusicQueue().peek();
        return next == null ? nextToPlayIdleMusicDetail : next.musicDetail();
    }

    public void stop() {
        MusicDetail previous;
        synchronized (playbackStateLock) {
            cancelLyricsUpdaterLocked();
            previous = currentlyPlayingMusicDetail;
            currentlyPlayingMusicDetail = MusicDetail.NONE;
            nextToPlayIdleMusicDetail = MusicDetail.NONE;
            musicDuration = null;
            musicStartTime = null;
            lyricLines = null;
            currentLyricLine = null;
            publishPlaybackStateLocked();
        }
        callMusicSwitchListeners(previous, MusicDetail.NONE);
        callLyricsUpdateListeners(null);
    }

    /** Atomically published playback state with an immutable lyrics list. */
    public record PlaybackSnapshot(MusicDetail musicDetail, MusicDetail nextToPlay,
                                   List<LyricLine> lyrics, Duration duration,
                                   ZonedDateTime startedAt) {
        public PlaybackSnapshot {
            lyrics = lyrics == null ? List.of() : List.copyOf(lyrics);
        }
    }
}
