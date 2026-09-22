package indi.mopelotus.musichud.client.audio;

import java.util.function.LongSupplier;

/** Progress is PCM delivered to the player, not bytes discarded while catching up. */
final class PlaybackDownloadProgress {
    static final long STALL_NANOS = 30_000_000_000L;
    private final LongSupplier clock;
    private long lastEnqueue;

    PlaybackDownloadProgress() { this(System::nanoTime); }
    PlaybackDownloadProgress(LongSupplier clock) { this.clock = clock; lastEnqueue = clock.getAsLong(); }
    void enqueued() { lastEnqueue = clock.getAsLong(); }
    void beforeRead(boolean dry) {
        if (dry && clock.getAsLong() - lastEnqueue >= STALL_NANOS)
            throw new IllegalStateException("Audio download made no playback progress for 30 seconds");
    }

    static boolean truncated(long decodedFrames, int sampleRate, long durationMillis) {
        if (decodedFrames < 0 || sampleRate <= 0 || durationMillis <= 0) return false;
        long tolerance = Math.max(2000, durationMillis / 20);
        long threshold = PcmPlaybackBuffer.frameAt(Math.max(0, durationMillis - tolerance), sampleRate);
        return decodedFrames < threshold;
    }
}
