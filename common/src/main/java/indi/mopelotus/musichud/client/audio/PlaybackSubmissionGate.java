package indi.mopelotus.musichud.client.audio;

import java.util.Objects;
import java.util.UUID;

/** One submission per playback session; refreshed resources keep the same claim. */
final class PlaybackSubmissionGate {
    private long generation = -1;
    private UUID sessionId;
    private boolean claimed;

    synchronized void activate(long generation, UUID sessionId) {
        if (generation <= this.generation) return;
        if (!Objects.equals(this.sessionId, sessionId)) claimed = false;
        this.generation = generation;
        this.sessionId = sessionId;
    }

    synchronized boolean claim(long generation, boolean valid) {
        if (!valid || sessionId == null || generation != this.generation || claimed) return false;
        claimed = true;
        return true;
    }

    synchronized boolean isClaimed(long generation) {
        return this.generation == generation && claimed;
    }

    synchronized void invalidate(long generation) {
        if (generation > this.generation) {
            this.generation = generation;
            sessionId = null;
            claimed = false;
        }
    }
}
