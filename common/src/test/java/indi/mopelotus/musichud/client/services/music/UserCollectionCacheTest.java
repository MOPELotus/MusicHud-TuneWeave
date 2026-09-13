package indi.mopelotus.musichud.client.services.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class UserCollectionCacheTest {
    @Test void completedModuleExpiresAndRejectsClockRollback() {
        var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        var cache = new UserCollectionCache<String>(clock::get);
        assertEquals("first", cache.load(false, () -> "first", Runnable::run).join());
        clock.set(300999);
        assertEquals("first", cache.load(false, () -> fail("Premature expiry"), Runnable::run).join());
        clock.set(301000);
        assertEquals("fresh", cache.load(false, () -> "fresh", Runnable::run).join());
        clock.set(1000);
        assertEquals("rollback", cache.load(false, () -> "rollback", Runnable::run).join());
    }

    @Test void lateSubscribersReceivePartialAndShareOnePendingRequest() {
        var cache = new UserCollectionCache<String>();
        List<Runnable> tasks = new ArrayList<>();
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        var publish = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        var pending = cache.loadProgress(false, callback -> {
            publish.set(callback); return () -> "complete";
        }, first::add, tasks::add);
        publish.get().accept("page one");
        assertSame(pending, cache.loadProgress(false, callback -> { throw new AssertionError("duplicate load"); }, second::add, tasks::add));
        assertEquals(List.of("page one"), second);
        publish.get().accept("page two");
        assertEquals(List.of("page one", "page two"), first);
        tasks.getFirst().run();
        assertEquals("complete", pending.join());
    }

    @Test void oldPagePublisherCannotDeliverAfterRefreshOrAccountInvalidation() {
        var cache = new UserCollectionCache<String>();
        var old = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        List<Runnable> tasks = new ArrayList<>();
        cache.loadProgress(false, callback -> { old.set(callback); return () -> "old"; },
                page -> fail("Old page reached UI"), tasks::add);
        cache.loadProgress(true, callback -> () -> "new", page -> {}, tasks::add);
        assertThrows(CancellationException.class, () -> old.get().accept("late"));
        cache.invalidate();
        assertThrows(CancellationException.class, () -> old.get().accept("later"));
    }

    @AfterEach
    void clear() {
        MusicEntityCache.clear();
    }

    @Test
    void accountSwitchRejectsOldCompletionAndPreservesNewCache() {
        var cache = new UserCollectionCache<String>();
        List<Runnable> tasks = new ArrayList<>();
        var old = cache.load(false, () -> "old account", tasks::add);
        cache.invalidate();
        var current = cache.load(false, () -> "new account", tasks::add);
        tasks.get(1).run();
        tasks.get(0).run();
        assertCancelled(old);
        assertEquals("new account", current.join());
        assertEquals("new account", cache.load(false, () -> fail("cache miss"), tasks::add).join());
    }

    @Test
    void refreshSupersedesPendingRequestWithoutAccountChange() {
        var cache = new UserCollectionCache<String>();
        List<Runnable> tasks = new ArrayList<>();
        var old = cache.load(false, () -> "old", tasks::add);
        var current = cache.load(true, () -> "refreshed", tasks::add);
        tasks.get(0).run();
        assertCancelled(old);
        tasks.get(1).run();
        assertEquals("refreshed", current.join());
    }

    @Test
    void entityScopeChangeRejectsCompletionEvenWithoutExplicitInvalidation() {
        var cache = new UserCollectionCache<String>();
        List<Runnable> tasks = new ArrayList<>();
        var pending = cache.load(false, () -> "stale", tasks::add);
        MusicEntityCache.clear();
        tasks.getFirst().run();
        assertCancelled(pending);
    }

    @Test
    void failureDoesNotBecomeCachedSuccess() {
        var cache = new UserCollectionCache<String>();
        var failed = cache.load(false, () -> { throw new IllegalStateException("offline"); }, Runnable::run);
        assertThrows(CompletionException.class, failed::join);
        assertEquals("retry", cache.load(false, () -> "retry", Runnable::run).join());
        assertEquals("refresh", cache.load(true, () -> "refresh", Runnable::run).join());
    }

    private static void assertCancelled(CompletableFuture<?> future) {
        assertInstanceOf(CancellationException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }
}
