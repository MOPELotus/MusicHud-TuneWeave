package indi.mopelotus.musichud.client.network.vanilla;

import com.google.common.cache.Cache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProxyCacheTest {
    @Test
    void onePlayerObjectRetainsOneProxy() throws Exception {
        Cache<Player, Proxy> cache = PlayerProxyCache.create();
        Player player = new Player(42, "world-a");
        Proxy first = cache.get(player, () -> new Proxy(player));
        assertSame(first, cache.get(player, () -> fail("Same player must reuse its proxy")));
        assertSame(player, first.player());
    }

    @Test
    void equalEntityIdsInDifferentWorldsCannotReuseTheDisconnectedPlayer() throws Exception {
        Cache<Player, Proxy> cache = PlayerProxyCache.create();
        Player oldPlayer = new Player(42, "paper-a");
        Player newPlayer = new Player(42, "velocity-backend-b");
        assertEquals(oldPlayer, newPlayer, "Models Minecraft Entity.equals by entity ID");
        Proxy oldProxy = cache.get(oldPlayer, () -> new Proxy(oldPlayer));
        Proxy newProxy = cache.get(newPlayer, () -> new Proxy(newPlayer));
        assertNotSame(oldProxy, newProxy);
        assertSame(newPlayer, newProxy.player());
        assertSame(oldPlayer, oldProxy.player());
        cache.invalidate(oldPlayer);
        assertSame(newProxy, cache.getIfPresent(newPlayer), "Retiring the old key must preserve its equal replacement");
    }

    @Test
    void respawnReplacementWithTheSameEntityIdGetsItsOwnProxy() throws Exception {
        Cache<Player, Proxy> cache = PlayerProxyCache.create();
        Player beforeRespawn = new Player(42, "same-world");
        Player afterRespawn = new Player(42, "same-world");
        Proxy first = cache.get(beforeRespawn, () -> new Proxy(beforeRespawn));
        Proxy replacement = cache.get(afterRespawn, () -> new Proxy(afterRespawn));
        assertNotSame(first, replacement);
        assertSame(afterRespawn, replacement.player());
        assertSame(replacement, cache.get(afterRespawn, () -> fail("Rebound player must keep its proxy")));
    }

    @Test
    void changingAnEntityIdDoesNotLoseTheSamePlayerProxy() throws Exception {
        Cache<Player, Proxy> cache = PlayerProxyCache.create();
        Player player = new Player(42, "world-a");
        Proxy first = cache.get(player, () -> new Proxy(player));
        player.entityId = 99;
        assertSame(first, cache.get(player, () -> fail("Entity ID changes must not change player identity")));
    }

    @Test
    void concurrentRequestsForOnePlayerCreateExactlyOneProxy() throws Exception {
        Cache<Player, Proxy> cache = PlayerProxyCache.create();
        Player player = new Player(42, "world-a");
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            ArrayList<Future<Proxy>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return cache.get(player, () -> {
                        creations.incrementAndGet();
                        return new Proxy(player);
                    });
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Proxy first = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<Proxy> future : futures) assertSame(first, future.get(5, TimeUnit.SECONDS));
            assertEquals(1, creations.get());
        }
    }

    private record Proxy(Player player) {}

    /** Matches the real Entity equality boundary without bootstrapping Minecraft. */
    private static final class Player {
        private int entityId;
        private final String world;

        private Player(int entityId, String world) {
            this.entityId = entityId;
            this.world = world;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Player player && player.entityId == entityId;
        }

        @Override public int hashCode() { return entityId; }
    }
}
