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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<ArrayDeque<LyricLine>> atomicLyricLines = new AtomicReference<>();
    private final ClientConfig clientConfig = ClientConfig.getInstance();
    private volatile JMTC jmtc;
    @Setter
    @Getter
    private Duration updateInAdvanceDuration = Duration.of(500, ChronoUnit.MILLIS);
    private MusicDetail smtcPlayingMusicDetail = null;
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

    final Runnable lyricUpdater = () -> {
        Thread thread = Thread.currentThread();
        lyricUpdaterVThread = thread;
        thread.setName("MHWorker-Lyrics-Updater");
        while (true) {
            if (this.musicStartTime == null) {
                break;
            }
            ArrayDeque<LyricLine> lyricLines1 = this.atomicLyricLines.get();
            if (lyricLines1 == null || lyricLines1.isEmpty()) break;
            LyricLine line = lyricLines1.peek();
            if (line != null) {
                if (line.getStartTime() != null) {
                    if (ZonedDateTime.now().isAfter(this.musicStartTime.plus(getCallTime(line)))) {
                        lyricLines1.poll();
                        currentLyricLine = line;
                        LyricLine next = lyricLines1.peek();
                        if (next == null) {
                            callLyricsUpdateListeners(line);
                            logger.debug("lyricsUpdater stopped due to no more lyrics");
                            break;
                        } else if (ZonedDateTime.now().isBefore(this.musicStartTime.plus(getCallTime(next)))) {
                            callLyricsUpdateListeners(line);
                            if (sleepUntil(this.musicStartTime, getCallTime(next))) {
                                logger.debug("lyricsUpdater interruption");
                            }
                        }
                    } else if (sleepUntil(this.musicStartTime, getCallTime(line))) {
                        logger.debug("lyricsUpdater interruption");
                    }
                } else {
                    lyricLines1.poll();
                }
            }
        }
        lyricUpdaterVThread = null;
    };

    private NowPlayingInfo() {
        Thread smtcThread = new Thread(this::jmtcLoop, "MH-SMTC");
        smtcThread.setDaemon(true);
        smtcThread.start();
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

    private void jmtcLoop() {
        jmtc = JMTC.getInstance(new JMTCSettings("Minecraft-MusicHud TuneWeave", MusicHud.DISPLAY_NAME));
        JMTCCallbacks jmtcCallbacks = new JMTCCallbacks();
        jmtcCallbacks.onPlay = () -> {
            MusicHud.EXECUTOR.execute(() -> {
                clientConfig.setMuted(false);
                clientConfig.save();
            });
            jmtc.setPlayingState(JMTCPlayingState.PLAYING);
            jmtc.updateDisplay();
        };
        jmtcCallbacks.onPause = () -> {
            MusicHud.EXECUTOR.execute(() -> {
                clientConfig.setMuted(true);
                clientConfig.save();
            });
            jmtc.setPlayingState(JMTCPlayingState.PAUSED);
            jmtc.updateDisplay();
        };
        jmtcCallbacks.onNext = () -> {
            MusicHud.EXECUTOR.execute(() -> {
                MusicService.getInstance().voteForSkipCurrent();
            });
        };

        jmtc.setEnabled(true);
        jmtc.setEnabledButtons(new JMTCEnabledButtons(
                true, true, false, true, false
        ));
        jmtc.setCallbacks(jmtcCallbacks);

        // begin in STOPPED state with track info loaded
        jmtc.setPlayingState(JMTCPlayingState.STOPPED);
        jmtc.setMediaType(JMTCMediaType.Music);
        jmtc.setParameters(new JMTCParameters(JMTCParameters.LoopStatus.Track, 1.0, 1.0, false));
        jmtc.updateDisplay();

        while (true) {
            MusicDetail musicDetail = currentlyPlayingMusicDetail == null ? MusicDetail.NONE : currentlyPlayingMusicDetail;
            boolean updateDisplay = false;
            if (musicDetail != smtcPlayingMusicDetail) {
                smtcPlayingMusicDetail = musicDetail;
                String artists = musicDetail.getArtists().stream()
                        .map(Artist::getName)
                        .reduce((a, b) -> a + " / " + b)
                        .orElse("");
                ArrayList<MusicDetail> albumTracks = new ArrayList<>(musicDetail.getAlbum().getMusicDetails());
                long durationMillis = musicDetail.getDurationMillis();
                jmtc.setTimelineProperties(new JMTCTimelineProperties(0L, durationMillis, 0L, durationMillis));
                URI artUri = null;
                String picUrl = musicDetail.getAlbum().getPicUrl();
                if (picUrl.startsWith("http")) {
                    try {
                        String suffix = "png";
                        String[] splits = picUrl.split("\\.");
                        if (splits.length > 1) {
                            suffix = splits[splits.length - 1];
                        }
                        Path tempFile = Files.createTempFile("MusicHud TuneWeave-SMTC-Album", "." + suffix);
                        tempFile.toFile().deleteOnExit();
                        artUri = ImageUtils.downloadAsync(picUrl, inputStream -> {
                            try {
                                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                return tempFile.toUri();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, false).join();
                    } catch (Exception e) {
                        logger.warn("Failed to download album art for SMTC", e);
                    }
                } else {
                    try {
                        Path tempFile = Files.createTempFile("MusicHud TuneWeave-SMTC-Icon", ".png");
                        tempFile.toFile().deleteOnExit();
                        try (InputStream iconStream = getClass().getResourceAsStream("/assets/musichud_tuneweave/icon.png")) {
                            if (iconStream != null) {
                                Files.copy(iconStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                artUri = tempFile.toUri();
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to set default SMTC icon", e);
                    }
                }
                jmtc.setMediaProperties(new JMTCMusicProperties(
                        smtcPlayingMusicDetail.getName(),
                        artists,
                        musicDetail.getAlbum().getName(),
                        artists,
                        new String[]{""},
                        albumTracks.size(),
                        albumTracks.indexOf(musicDetail),
                        artUri
                ));
                updateDisplay = true;
                jmtc.setPlayingState(JMTCPlayingState.PLAYING);
            }
            if (musicDetail != MusicDetail.NONE) {
                Duration playedDuration = getPlayedDuration();
                long position = playedDuration.toMillis();
                jmtc.setPosition(position);
//                System.out.println("position: " + position);
                if (getPlayedDuration().equals(musicDuration)) {
                    jmtc.setPlayingState(JMTCPlayingState.STOPPED);
                } else if (clientConfig.getMuted()) {
                    jmtc.setPlayingState(JMTCPlayingState.PAUSED);
                } else {
                    jmtc.setPlayingState(JMTCPlayingState.PLAYING);
                }
                updateDisplay = true;
            } else {
                jmtc.setPlayingState(JMTCPlayingState.CLOSED);
            }
            if (updateDisplay) {
                jmtc.updateDisplay();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
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
            this.atomicLyricLines.set(null);
            return;
        }
        try {
            ArrayDeque<LyricLine> parsed = lyricInfo.withWordByWordLyric()
                    ? WordByWordLyricParser.parse(musicDetail)
                    : FullLineLyricParser.parse(musicDetail);
            this.lyricLines = parsed;
            this.atomicLyricLines.set(new ArrayDeque<>(parsed));
        } catch (Exception e) {
            this.lyricLines = null;
            this.atomicLyricLines.set(null);
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

    private void startLyricsUpdater() {
        // SMTC state change picked up by jmtcLoop polling
        if (lyricLines != null && !lyricLines.isEmpty()) {
            if (lyricUpdaterVThread == null) {
                MusicHud.EXECUTOR.execute(lyricUpdater);
            } else {
                lyricUpdaterVThread.interrupt();
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
        if (!MusicService.getInstance().getMusicQueue().isEmpty()) {
            return MusicService.getInstance().getMusicQueue().peek().musicDetail();
        } else {
            return nextToPlayIdleMusicDetail;
        }
    }

    public void stop() {
        if (lyricUpdaterVThread != null) {
            lyricUpdaterVThread.interrupt();
        }
        lyricUpdaterVThread = null;
        MusicDetail previous;
        synchronized (playbackStateLock) {
            previous = currentlyPlayingMusicDetail;
            currentlyPlayingMusicDetail = MusicDetail.NONE;
            nextToPlayIdleMusicDetail = MusicDetail.NONE;
            musicDuration = null;
            musicStartTime = null;
            lyricLines = null;
            atomicLyricLines.set(null);
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
