package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class LocalIdlePlaySourceStateTest {
    @Test void persistedReferenceRestoresWithoutWarmNumericMapper() {
        var source = new IdlePlaySource(42, Playlist.class, indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL)
                .withReference("netease:playlist:42");
        Set<IdlePlaySource> config = new HashSet<>(Set.of(source));
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {},
                (type, id) -> { throw new AssertionError("Cold restore must use stable source reference"); },
                sent::add, Runnable::run, (type, reference) -> {
                    assertEquals("netease:playlist:42", reference);
                    return CompletableFuture.completedFuture(playlist());
                });
        state.loadFromConfig();
        assertEquals(1, state.getSources().size());
        assertEquals(1, sent.size());
        assertEquals(indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL, state.getPlayMode(playlist()));
    }

    @Test void removedPartialSourceCannotReturnWhenItsDetailsArrive() {
        var partial = playlist(); partial.setMusicTrackCount(2);
        Set<IdlePlaySource> config = new HashSet<>();
        List<C2SPayload> sent = new ArrayList<>();
        var pending = new CompletableFuture<Playlist>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {}, (type, id) -> pending, sent::add, Runnable::run);
        state.add(partial);
        assertTrue(sent.isEmpty());
        state.remove(partial);
        pending.complete(playlist());
        assertEquals(1, sent.size());
        assertTrue(state.getSources().isEmpty());
    }

    @Test void selectedModeIsPersistedAndIncludedInClientSnapshot() {
        Set<IdlePlaySource> config = new HashSet<>();
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {},
                (type, id) -> CompletableFuture.completedFuture(playlist()), sent::add, Runnable::run);
        state.setPlayMode(playlist(), indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL);
        assertEquals(indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL, config.iterator().next().getMode());
        var message = (indi.mopelotus.musichud.network.payloads.pushMessages.c2s.AddToIdlePlaySourceMessage) sent.getLast();
        assertEquals(indi.mopelotus.musichud.beans.api.IdlePlayMode.SEQUENTIAL, message.idlePlaySource().getMode());
    }

    @Test
    void userRemovalWhileRestoreIsPendingCannotBeUndoneByLateDetails() {
        Set<IdlePlaySource> config = new HashSet<>(Set.of(new IdlePlaySource(42, Playlist.class)));
        var pending = new CompletableFuture<Playlist>();
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {},
                (type, id) -> pending, sent::add, Runnable::run);
        state.loadFromConfig();
        state.remove(playlist());
        pending.complete(playlist());
        assertTrue(config.isEmpty());
        assertTrue(state.getSources().isEmpty());
        assertEquals(1, sent.size());
        assertInstanceOf(indi.mopelotus.musichud.network.payloads.pushMessages.c2s.RemoveFromIdlePlaySourceMessage.class,
                sent.getFirst());
    }

    @Test
    void failedSiblingDoesNotDiscardSuccessfulSourceAndRetryCanRecover() {
        Set<IdlePlaySource> config = new HashSet<>(Set.of(
                new IdlePlaySource(42, Playlist.class), new IdlePlaySource(43, Playlist.class)));
        Map<Long, CompletableFuture<Playlist>> requests = new HashMap<>();
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {}, (type, id) -> {
            var future = new CompletableFuture<Playlist>();
            requests.put(id, future);
            return future;
        }, sent::add, Runnable::run);
        state.loadFromConfig();
        requests.get(43L).completeExceptionally(new IllegalStateException("offline"));
        requests.get(42L).complete(playlist());
        assertFalse(state.isLoaded());
        assertEquals(1, state.getSources().size());
        assertEquals(42, state.getSources().iterator().next().getId());
        assertEquals(1, sent.size());
        state.loadFromConfig();
        requests.get(42L).complete(playlist());
        requests.get(43L).complete(Playlist.fromTuneWeave(43, "netease:playlist:43", "Recovered", "",
                0, 0, Profile.ANONYMOUS));
        assertEquals(2, state.getSources().size());
        assertTrue(state.isLoaded());
    }

    @Test
    void resetRejectsQueuedCompletionThenReconnectPublishesOnce() {
        Set<IdlePlaySource> config = new HashSet<>(Set.of(new IdlePlaySource(42, Playlist.class)));
        List<Runnable> tasks = new ArrayList<>();
        List<CompletableFuture<Playlist>> requests = new ArrayList<>();
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {}, (type, id) -> {
            var future = new CompletableFuture<Playlist>();
            requests.add(future);
            return future;
        }, sent::add, tasks::add);
        state.loadFromConfig();
        state.loadFromConfig();
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        requests.getFirst().complete(playlist());
        state.reset();
        tasks.removeFirst().run();
        assertTrue(state.getSources().isEmpty());
        assertTrue(sent.isEmpty());
        state.loadFromConfig();
        tasks.removeFirst().run();
        requests.get(1).complete(playlist());
        tasks.removeFirst().run();
        assertEquals(1, sent.size());
        assertEquals(1, state.getSources().size());
        assertEquals(1, config.size());
    }

    @Test
    void failedDetailsDoNotPublishAndUnsupportedTypeNeverCallsLoader() {
        Set<IdlePlaySource> config = new HashSet<>(Set.of(new IdlePlaySource(42, Playlist.class)));
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> fail("unexpected save"),
                (type, id) -> CompletableFuture.failedFuture(new IllegalStateException("offline")),
                sent::add, Runnable::run);
        state.loadFromConfig();
        assertTrue(sent.isEmpty());
        assertTrue(state.getSources().isEmpty());
        assertFalse(state.isLoaded(), "failed batch must remain retryable");
        state.loadFromConfig();
        assertThrows(java.util.concurrent.CompletionException.class, () -> state.load(String.class, 1).join());
    }

    @Test
    void synchronousLoaderFailureLeavesBatchRetryable() {
        Set<IdlePlaySource> config = new HashSet<>(Set.of(new IdlePlaySource(42, Playlist.class)));
        List<C2SPayload> sent = new ArrayList<>();
        var state = new LocalIdlePlaySourceState(() -> config, () -> {}, (type, id) -> {
            throw new IllegalStateException("setup failure");
        }, sent::add, Runnable::run);
        state.loadFromConfig();
        assertFalse(state.isLoaded());
        assertTrue(sent.isEmpty());
    }

    private static Playlist playlist() {
        return Playlist.fromTuneWeave(42, "netease:playlist:42", "Test", "", 0, 0, Profile.ANONYMOUS);
    }
}
