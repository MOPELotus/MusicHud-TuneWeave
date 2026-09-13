package indi.mopelotus.musichud.client.services.music;

import java.util.concurrent.atomic.AtomicLong;

/** Generation gate for asynchronous favorite-intelligence loads. */
final class FavoriteIntelligenceState {
    private final AtomicLong generation = new AtomicLong();
    private volatile Object playlist;
    private volatile boolean enabled;
    private long loadingGeneration = -1;

    synchronized boolean beginLoad(long expected, Object source) {
        if (!accepts(expected, source) || loadingGeneration == expected) return false;
        loadingGeneration = expected;
        return true;
    }

    synchronized void endLoad(long expected) {
        if (loadingGeneration == expected) loadingGeneration = -1;
    }

    synchronized boolean isLoading() {
        return enabled && loadingGeneration == generation.get();
    }

    synchronized long enable(Object playlist) {
        this.playlist = playlist;
        enabled = true;
        return generation.incrementAndGet();
    }

    synchronized long disable() {
        enabled = false;
        playlist = null;
        return generation.incrementAndGet();
    }

    boolean accepts(long expectedGeneration, Object expectedPlaylist) {
        return enabled && generation.get() == expectedGeneration && playlist == expectedPlaylist;
    }

    long generation() {
        return generation.get();
    }
}
