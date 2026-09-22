package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedAudioSourcesTest {
    @Test void tracksContextAndDoesNotClaimReusedForeignIds() {
        var sources = new OwnedAudioSources();
        var lease = sources.register(100, 3);
        assertTrue(sources.owns(100, 3));
        assertFalse(sources.owns(200, 3));
        assertFalse(sources.owns(100, 4));
        sources.release(lease);
        assertFalse(sources.owns(100, 3));
    }

    @Test void lateReleaseCannotEraseNewOwnership() {
        var sources = new OwnedAudioSources();
        var old = sources.register(100, 3);
        var fresh = sources.register(100, 3);
        sources.release(old);
        assertTrue(sources.owns(100, 3));
        sources.release(fresh); sources.release(fresh);
        assertFalse(sources.owns(100, 3));
        assertThrows(IllegalArgumentException.class, () -> sources.register(0, 3));
        assertThrows(IllegalArgumentException.class, () -> sources.register(100, 0));
    }
    @Test @org.junit.jupiter.api.Timeout(5)
    void effectCleanupFinishesBeforeRetirementAndIgnoresOtherContexts() throws Exception {
        var sources = new OwnedAudioSources();
        var owned = sources.register(100, 3);
        sources.register(200, 4);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var visited = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var cleanup = workers.submit(() -> sources.forEach(100, id -> {
                visited.add(id); entered.countDown();
                try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            entered.await();
            var retiring = workers.submit(() -> sources.release(owned));
            assertFalse(retiring.isDone());
            finish.countDown(); cleanup.get(); retiring.get();
        }
        sources.forEach(100, visited::add);
        assertEquals(java.util.List.of(3), visited);
        assertTrue(sources.owns(200, 4));
    }
}
