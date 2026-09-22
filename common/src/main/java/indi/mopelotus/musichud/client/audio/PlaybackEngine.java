package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.audio.StreamAudioPlayer.Status;
import indi.mopelotus.musichud.beans.music.Fee;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.client.audio.decoder.*;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.hud.renderer.PlayingStatusRenderer;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.PlaybackResourceFailureMessage;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.sounds.SoundSource;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.net.SocketException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class PlaybackEngine implements PlaybackHandoff.Lane {
    private volatile float mixGain = 1;
    private volatile boolean disposed;

    @Override public void gain(float gain) { mixGain = Math.clamp(gain, 0, 1); }
    @Override public void discard() { disposed = true; stop(); }
    synchronized PlaybackSession session() {
        return recovery == null ? currentPlaybackSession : recovery.session();
    }
    private static final int BUFFER_COUNT = 8;
    private static final int BUFFER_SIZE = 65536;
    private static final int AUDIO_BUFFER_CAPACITY = 60;
    private static final long PLAY_LOOP_SLEEP_MS = 40;// 主循环轮询间隔
    private static final Logger LOGGER = MusicHud.getLogger(PlaybackEngine.class);
    private final ClientConfig clientConfig;

    PlaybackEngine() { this(ClientConfig.getInstance()); }

    PlaybackEngine(ClientConfig clientConfig) { this.clientConfig = Objects.requireNonNull(clientConfig); }
    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);
    private EarlyEofRecovery earlyEofRecovery = new EarlyEofRecovery();
    private final AtomicLong playbackGeneration = new AtomicLong();
    @Getter
    private final Set<Consumer<Status>> statusChangeListener = ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<?> playingFuture;
    private volatile CompletableFuture<?> downloadFuture;
    private MusicDetail currentMusicDetail;
    private volatile AudioDecoder currentDecoder;
    private final PlaybackDecoderSlot decoderSlot = new PlaybackDecoderSlot();
    private final PlaybackStartGate startGate = new PlaybackStartGate();
    private final PcmPlaybackBuffer pcm = new PcmPlaybackBuffer(AUDIO_BUFFER_CAPACITY);
    private PcmPlaybackController playbackController;
    private long playedBytes;
    private float lastGain = Float.NaN;
    private volatile ZonedDateTime serverStartTime;
    private Future<?> downloadThreadFuture;
    private Future<?> playThreadFuture;
    private volatile boolean directPlayback;
    private volatile MusicResourceInfo directMusicResourceInfo = MusicResourceInfo.NONE;
    private volatile MusicResourceInfo currentResourceInfo = MusicResourceInfo.NONE;
    private final PlaybackSubmissionGate scrobbleGate = new PlaybackSubmissionGate();
    private final PlaybackListeningLedger listeningLedger = new PlaybackListeningLedger();
    private java.util.function.BiConsumer<Long, MusicResourceInfo> preparedScrobble;
    private volatile PlaybackSession currentPlaybackSession = PlaybackSession.NONE;
    private Recovery recovery;

    private record Recovery(PlaybackSession session, long consumedMillis, boolean submitted,
                            java.util.function.BiConsumer<Long, MusicResourceInfo> submitter) {}

    /** Retire all old device/decoder work; only account-bound listening bookkeeping survives. */
    synchronized PlaybackEngine replaceForRecovery(PlaybackSession expected) {
        PlaybackEngine replacement = new PlaybackEngine(clientConfig);
        if (recovery != null && expected.sessionId().equals(recovery.session().sessionId())) {
            // The previous replacement may still be waiting for its asynchronous starter.
            replacement.recovery = recovery;
        } else if (expected.sessionId().equals(currentPlaybackSession.sessionId())) {
            long generation = playbackGeneration.get();
            replacement.recovery = new Recovery(currentPlaybackSession, listeningLedger.playedMillis(generation),
                    scrobbleGate.isClaimed(generation), preparedScrobble);
        }
        if (expected.sessionId().equals(session().sessionId())) replacement.earlyEofRecovery = earlyEofRecovery;
        // Recovery is not a new scrobble boundary. Preserve its original account capture in the replacement.
        preparedScrobble = null;
        recovery = null;
        discard();
        return replacement;
    }

    private AudioDecoder loadAudioDecoder(String identifier, FormatType formatType, java.util.Map<String, String> headers) {
        return AudioDecoderFactory.open(identifier, formatType, headers, true, true);
    }

    private AudioDecoder loadPublicAudioDecoder(String identifier, FormatType formatType,
                                                java.util.Map<String, String> headers) {
        return AudioDecoderFactory.openPublicResource(identifier, formatType, headers, true, true);
    }

    public Status getStatus() {
        return status.get();
    }

    private void setStatus(Status status) {
        if (this.status.get() != status) {
            this.status.set(status);
            statusChangeListener.forEach(c -> c.accept(status));
        }
    }

    public synchronized CompletableFuture<ZonedDateTime> playSessionAsync(PlaybackSession playbackSession) {
        if (disposed) return CompletableFuture.failedFuture(new CancellationException("Playback engine was retired"));
        if (playbackSession == null || !playbackSession.isActive()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Public playback session is not active"));
        }
        earlyEofRecovery.activate(playbackSession.sessionId());
        boolean newSession = !playbackSession.sessionId().equals(currentPlaybackSession.sessionId());
        if (newSession || !playbackSession.resourceInfo().getResolvedTrackReference().equals(currentPlaybackSession.resourceInfo().getResolvedTrackReference())) {
            submitScrobble(playbackGeneration.get());
        }
        Recovery restored = recovery;
        recovery = null;
        if (restored != null && restored.session().sessionId().equals(playbackSession.sessionId())) {
            preparedScrobble = restored.submitter();
        } else if (newSession) {
            preparedScrobble = TuneWeaveClientService.getInstance().prepareScrobble(playbackSession.musicDetail());
        }
        long generation = playbackGeneration.incrementAndGet();
        stopInternal();
        directPlayback = false;
        directMusicResourceInfo = MusicResourceInfo.NONE;
        currentPlaybackSession = playbackSession;
        scrobbleGate.activate(generation, playbackSession.sessionId());
        listeningLedger.begin(generation, playbackSession.sessionId(), playbackSession.resourceInfo().getResolvedTrackReference());
        if (restored != null && restored.session().sessionId().equals(playbackSession.sessionId())) {
            if (restored.submitted()) scrobbleGate.claim(generation, true);
            if (restored.session().resourceInfo().getResolvedTrackReference()
                    .equals(playbackSession.resourceInfo().getResolvedTrackReference())) {
                listeningLedger.restoreConsumed(generation, restored.consumedMillis());
            }
        }
        return playAsyncInternal(playbackSession.musicDetail(), playbackSession.startTime(), generation);
    }

    public synchronized CompletableFuture<ZonedDateTime> playDirectAsync(
            String identifier, FormatType formatType, ZonedDateTime startTime) {
        if (disposed) return CompletableFuture.failedFuture(new CancellationException("Playback engine was retired"));
        submitScrobble(playbackGeneration.get());
        long generation = playbackGeneration.incrementAndGet();
        stopInternal();
        directPlayback = true;
        currentPlaybackSession = PlaybackSession.NONE;
        scrobbleGate.invalidate(generation);
        listeningLedger.begin(generation, null);
        directMusicResourceInfo = new MusicResourceInfo(
                0L,
                AudioFormatDetector.normalizeIdentifier(identifier),
                0,
                0L,
                formatType == null ? FormatType.AUTO : formatType,
                "",
                Fee.UNSET,
                0
        );
        return playAsyncInternal(MusicDetail.NONE, startTime, generation);
    }

    private @NotNull CompletableFuture<ZonedDateTime> playAsyncInternal(
            MusicDetail musicDetail, ZonedDateTime startTime, long generation) {
        if (disposed || generation != playbackGeneration.get())
            return CompletableFuture.failedFuture(new CancellationException("Playback generation was superseded"));
        currentMusicDetail = musicDetail;
        setStatus(Status.BUFFERING);
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<ZonedDateTime> started = startGate.begin();
        serverStartTime = startTime;
        downloadFuture = new CompletableFuture<>();
        playingFuture = new CompletableFuture<>();
        CompletableFuture<?> download = downloadFuture;
        downloadThreadFuture = MusicHud.EXECUTOR.submit(() -> {
            Thread.currentThread().setName("MHWorker-Downloader");
            try {
                downloadAudioWithRetry(startTime != null, ready, generation);
            } catch (Exception error) {
                synchronized (this) {
                    if (generation != playbackGeneration.get() || download != downloadFuture) return;
                    pcm.finish(pcm.token(), error);
                    download.completeExceptionally(error);
                    ready.completeExceptionally(error);
                    started.completeExceptionally(error);
                    setStatus(Status.ERROR);
                }
            }
        });
        ready.thenRun(() -> {
            synchronized (this) {
                if (generation != playbackGeneration.get() || download != downloadFuture || disposed) return;
                playThreadFuture = MusicHud.EXECUTOR.submit(() -> playAudio(started, generation));
            }
        });
        return started;
    }

    private void playAudio(CompletableFuture<ZonedDateTime> started, long generation) {
        CompletableFuture<?> playing;
        PcmPlaybackController controller;
        synchronized (this) {
            if (generation != playbackGeneration.get() || disposed) return;
            playing = playingFuture;
            controller = new PcmPlaybackController(pcm, OpenAlPlaybackDevice.LWJGL, listeningLedger, generation);
            playbackController = controller;
        }
        boolean began = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                PcmPlaybackController.Result result;
                synchronized (this) {
                    if (generation != playbackGeneration.get() || playing != playingFuture || disposed) return;
                    if (serverStartTime == null && pcm.peek(pcm.token()) != null) serverStartTime = ZonedDateTime.now();
                    long position = serverStartTime == null ? 0 : Math.max(0, Duration.between(serverStartTime, ZonedDateTime.now()).toMillis());
                    float gain = clientConfig.getMuted() ? 0 : mixGain * (float) clientConfig.getSoundVolume() / 100
                            * (clientConfig.getMixWithVanillaSoundVolume()
                            ? Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC) : 1);
                    if (Float.compare(lastGain, gain) != 0) {
                        lastGain = gain;
                        PlayingStatusRenderer.getInstance().updateStatus(null);
                    }
                    var outputMode = clientConfig.getAudioOutputMode();
                    if (outputMode == indi.mopelotus.musichud.beans.music.AudioOutputMode.DISCRETE_ONLY
                            && currentDecoder != null && currentDecoder.hasDownmixedChannels())
                        throw new IllegalStateException("The source layout cannot be rendered as discrete multichannel audio");
                    result = controller.tick(position, outputMode, gain);
                    switch (result) {
                        case PLAYING -> {
                            setStatus(Status.PLAYING);
                            started.complete(serverStartTime);
                            if (!began && clientConfig.getDisableVanillaMusic()) {
                                Minecraft.getInstance().execute(() -> {
                                    synchronized (PlaybackEngine.this) {
                                        if (!disposed && generation == playbackGeneration.get() && playing == playingFuture)
                                            Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
                                    }
                                });
                            }
                            began = true;
                        }
                        case BUFFERING -> setStatus(Status.BUFFERING);
                        case RECOVERING -> setStatus(Status.RETRYING);
                        case COMPLETED -> {
                            // EOF (including a timeline already elapsed during reload) is not a retryable download error.
                            submitScrobble(generation);
                            setStatus(Status.IDLE);
                            started.complete(Objects.requireNonNullElseGet(serverStartTime, ZonedDateTime::now));
                            return;
                        }
                    }
                }
                Thread.sleep(result == PcmPlaybackController.Result.RECOVERING ? 100 : PLAY_LOOP_SLEEP_MS);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            synchronized (this) {
                if (generation == playbackGeneration.get() && playing == playingFuture) {
                    LOGGER.error("Audio playback failed", error);
                    setStatus(Status.ERROR);
                    started.completeExceptionally(error);
                }
            }
        } finally {
            synchronized (this) {
                controller.close();
                if (playbackController == controller) {
                    playbackController = null;
                    if (downloadFuture != null) downloadFuture.cancel(true);
                    if (downloadThreadFuture != null) downloadThreadFuture.cancel(true);
                    decoderSlot.advance(generation);
                    currentDecoder = null;
                }
                playing.complete(null);
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private void downloadAudioWithRetry(boolean forceSync,
                                        CompletableFuture<Void> downloadInitializedFuture,
                                        long generation) {
        CompletableFuture<?> currentPlayingFuture = playingFuture;
        CompletableFuture<?> currentDownloadFuture = downloadFuture;
        PcmPlaybackBuffer.Token decoderToken = pcm.token();

        int localRetryCount = 0;
        boolean forceSyncInternal = forceSync;
        boolean localDirectPlayback = directPlayback;
        MusicResourceInfo localDirectResource = directMusicResourceInfo;
        PlaybackSession localPlaybackSession = currentPlaybackSession;

        MusicResourceInfo musicResourceInfo = MusicResourceInfo.NONE;
        List<String> resourceUrls = List.of();
        int resourceUrlIndex = 0;
        boolean refreshResource = true;
        while (!currentDownloadFuture.isDone() && currentDownloadFuture == downloadFuture
                && generation == playbackGeneration.get()) {
            try {
                if (localDirectPlayback && refreshResource) {
                    musicResourceInfo = localDirectResource;
                    resourceUrls = musicResourceInfo.getCandidateUrls();
                    resourceUrlIndex = 0;
                    refreshResource = false;
                } else if (refreshResource) {
                    musicResourceInfo = localPlaybackSession.resourceInfo();
                    resourceUrls = musicResourceInfo.getCandidateUrls();
                    resourceUrlIndex = 0;
                    refreshResource = false;
                }
                synchronized (this) {
                    if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                    currentResourceInfo = musicResourceInfo;
                }
                if (resourceUrls.isEmpty()) throw new IllegalStateException("No audio resource URLs available");

                LOGGER.debug("Starting audio download (attempt {})", localRetryCount + 1);
                if (resourceUrlIndex > 0) {
                    LOGGER.info("Trying backup audio URL {}/{}", resourceUrlIndex, resourceUrls.size() - 1);
                }

                AudioDecoder decoder = localDirectPlayback
                        ? loadAudioDecoder(resourceUrls.get(resourceUrlIndex),
                        musicResourceInfo.getType(), musicResourceInfo.getHeaders())
                        : loadPublicAudioDecoder(resourceUrls.get(resourceUrlIndex),
                        musicResourceInfo.getType(), musicResourceInfo.getHeaders());
                synchronized (this) {
                    if (generation != playbackGeneration.get() || currentDownloadFuture.isDone()
                            || currentDownloadFuture != downloadFuture) {
                        decoder.close();
                        return;
                    }
                    if (!decoderSlot.adopt(generation, decoder)) return;
                    currentDecoder = decoder;
                    playedBytes = 0;
                    decoderToken = pcm.reset(generation);
                    downloadInitializedFuture.complete(null);
                    if (status.get() != Status.ERROR && status.get() != Status.RETRYING) setStatus(Status.BUFFERING);
                }

                PlaybackDownloadProgress progress = new PlaybackDownloadProgress();
                if (forceSyncInternal) {
                    syncPlaying(currentDownloadFuture, decoder, generation, progress, decoderToken);
                }

                int initialBuffers = 0;
                while (!currentDownloadFuture.isDone() && currentDownloadFuture == downloadFuture && initialBuffers < BUFFER_COUNT * 2) {
                    progress.beforeRead(pcm.empty(decoderToken));
                    byte[] audioData = decoder.readChunk(BUFFER_SIZE);
                    if (audioData == null) break;
                    if (currentDownloadFuture.isDone() || currentDownloadFuture != downloadFuture) break;

                    if (!pcm.offer(decoderToken, new PcmPlaybackBuffer.Chunk(audioData, decoder.getFormat(),
                            decoder.getSampleRate(), playedBytes / decoder.getFrameSize()))) return;
                    synchronized (this) {
                        if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                        playedBytes += audioData.length;
                        progress.enqueued();
                    }
                    initialBuffers++;
                }

                while (!currentDownloadFuture.isDone() && currentDownloadFuture == downloadFuture) {
                    // 保持解码器头与墙钟对齐（无论下载缓冲是否充足）
                    syncPlaying(currentDownloadFuture, decoder, generation, progress, decoderToken);

                    progress.beforeRead(pcm.empty(decoderToken));
                    byte[] audioData = decoder.readChunk(BUFFER_SIZE);
                    if (audioData == null) break;

                    if (currentPlayingFuture.isDone() || currentDownloadFuture != downloadFuture) break;

                    if (!pcm.offer(decoderToken, new PcmPlaybackBuffer.Chunk(audioData, decoder.getFormat(),
                            decoder.getSampleRate(), playedBytes / decoder.getFrameSize()))) return;
                    synchronized (this) {
                        if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                        playedBytes += audioData.length;
                        progress.enqueued();
                    }
                }

                if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture
                        || currentDownloadFuture.isCancelled() || Thread.currentThread().isInterrupted()) return;
                long expectedDuration = musicResourceInfo.getTime() > 0 ? musicResourceInfo.getTime()
                        : currentMusicDetail.getDurationMillis();
                if (!localDirectPlayback && PlaybackDownloadProgress.truncated(
                        playedBytes / decoder.getFrameSize(), decoder.getSampleRate(), expectedDuration)) {
                    synchronized (this) {
                        if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                        if (earlyEofRecovery.claim(localPlaybackSession.sessionId(), localPlaybackSession.revision())) {
                            LOGGER.warn("Audio stream ended early; requesting public resource refresh for revision {}",
                                    localPlaybackSession.revision());
                            IClientNetworkService.getInstance().sendToServer(new PlaybackResourceFailureMessage(
                                    localPlaybackSession.sessionId(), localPlaybackSession.revision()));
                        }
                    }
                    // Retain decoded audio while the server coordinates a new public revision.
                    // Bad duration metadata cannot cause an unbounded refresh loop.
                }
                LOGGER.debug("Audio download completed");
                pcm.finish(decoderToken, null);
                currentDownloadFuture.complete(null);
                break;
            } catch (InterruptedException e) {
                LOGGER.debug("Download stopped by interruption");
                break;
            } catch (Exception e) {
                if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture || currentDownloadFuture.isDone()) return;
                if (e instanceof SocketException e1 && "Closed by interrupt".equals(e1.getMessage())) break;
                LOGGER.error("Download error (attempt {})\n{} : {}", localRetryCount + 1, e.getClass().getSimpleName(), e.getMessage());

                String failureMessage = e.getMessage();
                if (e.getCause() instanceof java.util.concurrent.TimeoutException
                        || (failureMessage != null && (failureMessage.contains("Timeout") || failureMessage.contains("timeout")))) {
                    failureMessage = I18n.get(MusicHud.MOD_ID + ".error.cause.timeout");
                }
                if (failureMessage == null || failureMessage.isBlank()) {
                    failureMessage = e.getClass().getSimpleName();
                }
                ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".error.downloadingAudioStream")
                        .replace("{trial}", String.valueOf(localRetryCount + 1))
                        .replace("{message}", failureMessage));

                synchronized (this) {
                    if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                    // Reset only when the next decoder has been opened; retained PCM can finish meanwhile.
                    playedBytes = 0;
                }
                forceSyncInternal = true;
                localRetryCount++;
                boolean hasBackup = resourceUrlIndex + 1 < resourceUrls.size();
                if (hasBackup) {
                    resourceUrlIndex++;
                } else {
                    resourceUrlIndex = 0;
                    if (localRetryCount >= 3 && generation == playbackGeneration.get()) {
                        PlaybackSession failedSession = localPlaybackSession;
                        if (!localDirectPlayback) IClientNetworkService.getInstance().sendToServer(
                                new PlaybackResourceFailureMessage(failedSession.sessionId(), failedSession.revision()));
                        throw new IllegalStateException("Audio resource attempts exhausted", e);
                    }
                }
                synchronized (this) {
                    if (generation != playbackGeneration.get() || currentDownloadFuture != downloadFuture) return;
                    setStatus(Status.RETRYING);
                }

                try {
                    int delay = hasBackup ? 100 : Math.min(3000, localRetryCount * 500);
                    LOGGER.debug("Waiting {} ms before retry", delay);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    LOGGER.debug("Download thread interrupted");
                    break;
                }
            }
        }

        LOGGER.debug("Download task finished");
    }

    private synchronized void submitScrobble(long generation) {
        if (generation != playbackGeneration.get()) return;
        PlaybackSession session = currentPlaybackSession;
        if (!session.isActive()) return;
        MusicDetail detail = session.musicDetail();
        MusicResourceInfo resource = session.resourceInfo();
        if (detail == null || resource == null || resource == MusicResourceInfo.NONE) return;
        long duration = resource.getTime() > 0 ? resource.getTime() : detail.getDurationMillis();
        long played = Math.min(duration, listeningLedger.playedMillis(generation));
        var localPlayer = Minecraft.getInstance().player;
        if (!clientConfig.getScrobbleMode().allows(detail.getPusherInfo().getPlayerUUID(),
                localPlayer == null ? null : localPlayer.getUUID(), played)) return;
        Quality quality = resource.getActualQuality();
        if (quality == null || quality == Quality.NONE) return;
        if (!scrobbleGate.claim(generation, TuneWeaveClientService.scrobbleEligible(detail, played, resource))) return;
        var submitter = preparedScrobble;
        if (submitter == null) return;
        MusicHud.EXECUTOR.execute(() -> {
            try {
                submitter.accept(played, resource);
            } catch (RuntimeException error) {
                LOGGER.debug("Scrobble submission failed", error);
            }
        });
    }

    private void syncPlaying(CompletableFuture<?> download, AudioDecoder decoder, long generation,
                             PlaybackDownloadProgress progress, PcmPlaybackBuffer.Token token) {
        ZonedDateTime startTime = serverStartTime;
        if (startTime == null) return;
        int frameSize = decoder.getFrameSize();
        long targetFrames = PcmPlaybackBuffer.frameAt(Duration.between(startTime, ZonedDateTime.now()).toMillis(), decoder.getSampleRate());
        while (!download.isDone() && download == downloadFuture && generation == playbackGeneration.get()) {
            long remaining = targetFrames - playedBytes / frameSize;
            if (remaining <= 0) return;
            // Do not decode an unbounded allocation after a long pause, and never request a partial frame.
            long request = Math.min(remaining, BUFFER_SIZE / frameSize) * frameSize;
            progress.beforeRead(pcm.empty(token));
            byte[] chunk = decoder.readChunk(request);
            if (chunk == null) return;
            synchronized (this) {
                if (generation != playbackGeneration.get() || download != downloadFuture) return;
                playedBytes += chunk.length;
            }
        }
    }

    public synchronized void stop() {
        recovery = null;
        long generation = playbackGeneration.get();
        submitScrobble(generation);
        scrobbleGate.invalidate(playbackGeneration.incrementAndGet());
        listeningLedger.begin(playbackGeneration.get(), null);
        stopInternal();
        directPlayback = false;
        directMusicResourceInfo = MusicResourceInfo.NONE;
        currentPlaybackSession = PlaybackSession.NONE;
        currentMusicDetail = MusicDetail.NONE;
        currentResourceInfo = MusicResourceInfo.NONE;
        setStatus(Status.IDLE);
    }

    private void stopInternal() {
        // Invalidate queued producers and request interruption before releasing a decoder they may be reading.
        startGate.cancel();
        pcm.reset(playbackGeneration.get());
        if (downloadFuture != null) { downloadFuture.cancel(true); downloadFuture = null; }
        if (downloadThreadFuture != null) { downloadThreadFuture.cancel(true); downloadThreadFuture = null; }
        if (playingFuture != null) { playingFuture.cancel(true); playingFuture = null; }
        if (playThreadFuture != null) { playThreadFuture.cancel(true); playThreadFuture = null; }
        decoderSlot.advance(playbackGeneration.get());
        currentDecoder = null;
        if (playbackController != null) { playbackController.close(); playbackController = null; }
        playedBytes = 0;
        serverStartTime = null;
    }
}
