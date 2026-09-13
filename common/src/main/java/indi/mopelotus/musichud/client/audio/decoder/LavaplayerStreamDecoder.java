package indi.mopelotus.musichud.client.audio.decoder;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import org.lwjgl.openal.AL10;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LavaplayerStreamDecoder implements AudioDecoder {
    private static final long LOAD_TIMEOUT_SECONDS = 20;
    private static final long STUCK_TIMEOUT_MILLIS = 10_000L;

    private final AudioPlayer player;
    private final AudioTrack track;
    private final AudioTrackExecutor trackExecutor;
    private final PlaybackState playbackState;
    private final int format;
    private final int sampleRate;
    private final int channelCount;
    private final AudioPlayerManager playerManager;
    private byte[] currentFrameData;
    private int currentFrameOffset;
    private volatile boolean closed;

    private LavaplayerStreamDecoder(AudioPlayer player, AudioTrack track, PlaybackState playbackState,
                                    int format, int sampleRate, int channelCount, AudioPlayerManager playerManager) {
        this.player = player;
        this.track = track;
        this.trackExecutor = ((InternalAudioTrack) track).getActiveExecutor();
        this.playbackState = playbackState;
        this.format = format;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.playerManager = playerManager;
    }

    public static LavaplayerStreamDecoder open(String identifier) throws IOException {
        return open(identifier, Map.of());
    }

    public static LavaplayerStreamDecoder open(String identifier, Map<String, String> headers) throws IOException {
        return open(identifier, headers, false);
    }

    public static LavaplayerStreamDecoder openPublicResource(
            String identifier, Map<String, String> headers) throws IOException {
        return open(identifier, headers, true);
    }

    private static LavaplayerStreamDecoder open(String identifier, Map<String, String> headers,
                                                boolean enforcePublicResourcePolicy) throws IOException {
        AudioPlayerManager playerManager = createPlayerManager(headers, enforcePublicResourcePolicy);
        AudioTrack track;
        try {
            track = loadTrack(playerManager, identifier);
        } catch (IOException error) {
            playerManager.shutdown();
            throw error;
        }
        return start(playerManager, track);
    }

    static LavaplayerStreamDecoder start(AudioPlayerManager playerManager, AudioTrack track) {
        AudioPlayer player = playerManager.createPlayer();
        PlaybackState playbackState = new PlaybackState();
        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
                playbackState.endReason = endReason;
            }

            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                playbackState.failure = exception;
            }
        });
        player.setVolume(100);
        player.playTrack(track);
        int channelCount = StandardAudioDataFormats.COMMON_PCM_S16_LE.channelCount;
        int sampleRate = StandardAudioDataFormats.COMMON_PCM_S16_LE.sampleRate;
        int format = channelCount == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        return new LavaplayerStreamDecoder(player, track, playbackState, format, sampleRate, channelCount, playerManager);
    }

    @Override
    public byte[] readChunk(long maxSize) {
        if (closed || maxSize <= 0) {
            return null;
        }
        int boundedSize = (int) Math.min(maxSize, Integer.MAX_VALUE);
        byte[] result = new byte[boundedSize];
        int written = 0;
        try {
            while (written < boundedSize) {
                if (currentFrameData != null && currentFrameOffset < currentFrameData.length) {
                    int length = Math.min(boundedSize - written, currentFrameData.length - currentFrameOffset);
                    System.arraycopy(currentFrameData, currentFrameOffset, result, written, length);
                    currentFrameOffset += length;
                    written += length;
                    continue;
                }

                currentFrameData = null;
                currentFrameOffset = 0;
                throwIfPlaybackFailed();
                if (hasTrackEnded()) {
                    awaitTrackCompletion();
                    break;
                }

                AudioFrame frame;
                try {
                    frame = player.provide(STUCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException error) {
                    throwIfPlaybackFailed();
                    if (!hasTrackEnded()) throw new RuntimeException("Timed out while reading decoded audio", error);
                    awaitTrackCompletion();
                    break;
                }
                if (frame == null) {
                    throwIfPlaybackFailed();
                    if (hasTrackEnded()) {
                        awaitTrackCompletion();
                        break;
                    }
                    throw new RuntimeException("Timed out while reading decoded audio");
                }
                if (frame.isTerminator()) {
                    awaitTrackCompletion();
                    break;
                }
                currentFrameData = frame.getData();
                if (currentFrameData == null || currentFrameData.length == 0) {
                    currentFrameData = null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return written == 0 ? null : Arrays.copyOf(result, written);
        }
        return written == 0 ? null : Arrays.copyOf(result, written);
    }

    @Override
    public boolean seekToMillis(long positionMillis) {
        if (closed || !track.isSeekable()) {
            return false;
        }
        long targetPosition = Math.max(0L, positionMillis);
        long duration = track.getDuration();
        if (duration > 0L && duration != Long.MAX_VALUE) {
            targetPosition = Math.min(targetPosition, Math.max(0L, duration - 1L));
        }
        currentFrameData = null;
        currentFrameOffset = 0;
        track.setPosition(targetPosition);
        return true;
    }

    @Override
    public long getPositionMillis() {
        return closed ? -1L : track.getPosition();
    }

    @Override
    public int getFormat() {
        return format;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getFrameSize() {
        return channelCount * Short.BYTES;
    }

    @Override
    public SampleEncoding getSampleEncoding() {
        return SampleEncoding.PCM_S16_LE;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public String getBackendName() {
        return "lavaplayer";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        player.stopTrack();
        player.destroy();
        playerManager.shutdown();
    }

    private boolean hasTrackEnded() {
        return playbackState.endReason != null || player.getPlayingTrack() == null;
    }

    private void awaitTrackCompletion() throws InterruptedException {
        // Lavaplayer 2.2.7 publishes the terminator before reporting an exception. FINISHED
        // is published after that callback, so a drained buffer alone cannot establish success.
        // Keep the original executor: player.provide() detaches it when consuming a terminator.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STUCK_TIMEOUT_MILLIS);
        while (!closed && trackExecutor.getState() != AudioTrackState.FINISHED) {
            throwIfPlaybackFailed();
            if (System.nanoTime() - deadline >= 0) {
                throw new RuntimeException("Timed out waiting for decoded audio completion");
            }
            Thread.sleep(1);
        }
        if (!closed) throwIfPlaybackFailed();
    }

    private void throwIfPlaybackFailed() {
        FriendlyException failure = playbackState.failure;
        if (failure != null) {
            throw new RuntimeException("Failed to decode audio track", failure);
        }
        AudioTrackEndReason endReason = playbackState.endReason;
        if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            throw new RuntimeException("Audio track failed while decoding");
        }
    }

    private static AudioPlayerManager createPlayerManager(Map<String, String> headers,
                                                          boolean enforcePublicResourcePolicy) {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
        manager.setHttpBuilderConfigurator(builder -> {
            List<org.apache.http.Header> defaults = new ArrayList<>();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && value != null && !name.isBlank()) {
                        defaults.add(new org.apache.http.message.BasicHeader(name, value));
                    }
                });
            }
            if (!defaults.isEmpty()) {
                builder.setDefaultHeaders(defaults);
            }
            if (enforcePublicResourcePolicy) {
                builder.setRedirectStrategy(new SafeAudioRedirectStrategy());
                builder.setDnsResolver(SharedResourceValidator::resolvePublicAddresses);
            }
        });
        manager.registerSourceManager(new HttpAudioSourceManager());
        manager.registerSourceManager(new LocalAudioSourceManager());
        return manager;
    }

    private static AudioTrack loadTrack(AudioPlayerManager manager, String identifier) throws IOException {
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack selectedTrack = playlist.getSelectedTrack();
                if (selectedTrack != null) {
                    future.complete(selectedTrack);
                    return;
                }
                if (!playlist.getTracks().isEmpty()) {
                    future.complete(playlist.getTracks().getFirst());
                    return;
                }
                future.completeExceptionally(new IOException("Playlist is empty: " + playlist.getName()));
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new IOException("No playable audio track found for: " + identifier));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        try {
            return future.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading audio track", e);
        } catch (ExecutionException e) {
            Throwable cause = Objects.requireNonNullElse(e.getCause(), e);
            throw new IOException("Failed to load audio track: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while loading audio track: " + identifier, e);
        }
    }

    private static final class PlaybackState {
        private volatile AudioTrackEndReason endReason;
        private volatile FriendlyException failure;
    }
}
