package indi.mopelotus.musichud.client.audio;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Measures consumed music buffers, excluding queued data, inserted silence, seeking and mute. */
final class PlaybackListeningLedger {
    private record Buffer(double seconds, boolean music) {}
    private final LinkedHashMap<Integer, Buffer> queued = new LinkedHashMap<>();
    private long generation = -1;
    private UUID session;
    private String resolvedReference = "";
    private double consumedMusic;
    private double observedMusic;
    private double listenedSeconds;

    synchronized void begin(long generation, UUID session) {
        begin(generation, session, "");
    }

    synchronized void begin(long generation, UUID session, String resolvedReference) {
        if (generation <= this.generation) return;
        if (!Objects.equals(this.session, session) || !Objects.equals(this.resolvedReference, resolvedReference)) listenedSeconds = 0;
        this.generation = generation;
        this.session = session;
        this.resolvedReference = resolvedReference;
        discardQueued(generation);
    }

    synchronized void queue(long generation, int bufferId, int bytes, long bytesPerSecond, boolean music) {
        if (generation != this.generation || session == null) return;
        if (bytes < 0 || bytesPerSecond <= 0) throw new IllegalArgumentException("Invalid PCM duration");
        queued.put(bufferId, new Buffer((double) bytes / bytesPerSecond, music));
    }

    synchronized void processed(long generation, int bufferId) {
        if (generation != this.generation) return;
        Buffer buffer = queued.remove(bufferId);
        if (buffer != null && buffer.music()) consumedMusic += buffer.seconds();
    }

    /** AL_SEC_OFFSET is relative to the remaining source queue, including pending silence. */
    synchronized void observe(long generation, double offsetSeconds, boolean audible) {
        if (generation != this.generation || session == null || !Double.isFinite(offsetSeconds) || offsetSeconds < 0) return;
        double musicPosition = consumedMusic;
        double remaining = offsetSeconds;
        for (Buffer buffer : queued.values()) {
            double traversed = Math.min(remaining, buffer.seconds());
            if (buffer.music()) musicPosition += traversed;
            remaining -= traversed;
            if (remaining <= 0) break;
        }
        double delta = Math.max(0, musicPosition - observedMusic);
        if (audible) listenedSeconds += delta;
        observedMusic = Math.max(observedMusic, musicPosition);
    }

    synchronized void discardQueued(long generation) {
        if (generation != this.generation) return;
        queued.clear();
        consumedMusic = 0;
        observedMusic = 0;
    }

    synchronized long playedMillis(long generation) {
        return generation == this.generation ? (long) (listenedSeconds * 1000) : 0;
    }

    /** A replacement engine inherits consumed time, never a device's pending PCM/offset. */
    synchronized void restoreConsumed(long generation, long playedMillis) {
        if (generation != this.generation || session == null) return;
        if (playedMillis < 0) throw new IllegalArgumentException("Negative listening duration");
        listenedSeconds = playedMillis / 1000.0;
    }
}
