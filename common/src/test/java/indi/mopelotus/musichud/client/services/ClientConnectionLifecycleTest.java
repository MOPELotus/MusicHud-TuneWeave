package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionLifecycleTest {
    @Test void proxyPlayReplacementKeepsConnectionPolicyAndFinalQuitCleansCurrentBinding() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain();
            Peer old = f.player; Object oldListener = f.connection;
            f.connection = new Object(); f.player = new Peer(old.id);
            f.join(); f.drain();
            assertEquals(1, f.joins, "Same TCP must not rerun auto-connect or reset public playback");
            assertEquals(1, f.rebinds);
            assertSame(f.player, f.registry.sessionToken(old.id));
            f.lifecycle.quit(old, oldListener, peer -> fail("Late old PLAY quit"), () -> fail("Late cleanup"));
            assertEquals(0, f.resets);
            f.quit(); f.connection = null; f.drain();
            assertNull(f.registry.sessionToken(old.id));
            assertEquals(1, f.resets);
        }
    }

    @Test void firstConnectionActionSurvivesListenerReplacementBeforeItCanRun() {
        try (Fixture f = new Fixture()) {
            f.join();
            f.connection = new Object(); f.player = new Peer(f.player.id);
            f.join(); f.drain();
            assertEquals(1, f.joins);
            assertEquals(0, f.rebinds, "There was no accepted connection mode to continue yet");
            assertSame(f.player, f.registry.sessionToken(f.player.id));
        }
    }

    @Test void duplicatePlayJoinDoesNotInvalidateOrRepeatPendingConnectionAction() {
        try (Fixture f = new Fixture()) {
            f.join(); f.join(); f.drain();
            f.join(); f.drain();
            assertEquals(1, f.joins); assertEquals(0, f.rebinds);
        }
    }

    @Test void quitBeforeQueuedPlayReplacementDetachesTheActuallyRegisteredOldPlayer() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain(); UUID id = f.player.id;
            f.connection = new Object(); f.player = new Peer(id); f.join();
            f.quit(); f.connection = null; f.drain();
            assertNull(f.registry.sessionToken(id), "New binding had not replaced the old registry entry yet");
            assertEquals(1, f.joins); assertEquals(0, f.rebinds); assertEquals(1, f.resets);
        }
    }

    @Test void rapidProxySwitchesDiscardQueuedIntermediateBinding() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain();
            f.connection = new Object(); f.player = new Peer(f.player.id); f.join();
            Object middleListener = f.connection; Peer middlePlayer = f.player;
            f.connection = new Object(); f.player = new Peer(f.player.id); f.join();
            f.lifecycle.quit(middlePlayer, middleListener, peer -> fail("Intermediate quit"), () -> fail("Intermediate reset"));
            f.drain();
            assertEquals(1, f.joins); assertEquals(1, f.rebinds); assertEquals(0, f.resets);
            assertSame(f.player, f.registry.sessionToken(f.player.id));
        }
    }

    @Test void respawnBeforeInitialQueuedJoinStillRunsInitialConnectionPolicy() {
        try (Fixture f = new Fixture()) {
            f.join(); f.player = new Peer(f.player.id); f.tick(); f.drain();
            assertEquals(1, f.joins); assertEquals(0, f.rebinds);
            assertSame(f.player, f.registry.sessionToken(f.player.id));
        }
    }

    @Test void queuedQuitCannotResetNewWorldOrSameObjectRejoin() {
        for (boolean reuseObjects : List.of(false, true)) try (Fixture f = new Fixture()) {
            f.join(); f.drain();
            f.quit();
            if (!reuseObjects) { f.connection = new Object(); f.player = new Peer(f.player.id); }
            f.join(); f.drain();
            assertEquals(0, f.resets);
            assertEquals(2, f.joins);
            assertSame(f.player, f.registry.sessionToken(f.player.id));
        }
    }

    @Test void oldJoinAndQuitCallbacksCannotAffectReplacementWorld() {
        try (Fixture f = new Fixture()) {
            Peer old = f.player; Object oldConnection = f.connection;
            f.join();
            f.connection = new Object(); f.transport = new Object(); f.player = new Peer(old.id); f.join();
            f.lifecycle.quit(old, oldConnection, peer -> fail("Old quit must not detach new world"), () -> fail("old cleanup"));
            f.drain();
            assertEquals(1, f.joins);
            assertSame(f.player, f.registry.sessionToken(old.id));
        }
    }

    @Test void respawnReplacesOnlyLocalMembershipAndInvalidatesOldJoinWork() {
        try (Fixture f = new Fixture()) {
            Peer guest = new Peer(UUID.randomUUID());
            f.registry.join(guest);
            try {
                f.join(); f.drain();
                Peer old = f.player;
                f.player = new Peer(old.id);
                f.tick();
                assertSame(f.player, f.registry.sessionToken(old.id));
                assertSame(guest, f.registry.sessionToken(guest.id));
                assertEquals(1, f.rebinds);
                f.tick(); assertEquals(1, f.rebinds);
                f.registry.leave(old);
                assertSame(f.player, f.registry.sessionToken(old.id), "Late old-player detach cannot remove replacement");
            } finally { f.registry.leave(guest); }
        }
    }

    @Test void respawnThenQuitBeforeNextTickStillDetachesTheBoundOldMember() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain();
            UUID id = f.player.id;
            f.player = new Peer(id);
            f.quit(); f.connection = null; f.drain();
            assertNull(f.registry.sessionToken(id));
            assertEquals(1, f.resets);
            assertEquals(0, f.rebinds);
        }
    }

    @Test void differentPhysicalConnectionNeverGetsRespawnRebindingFromOldWorld() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain();
            f.connection = new Object(); f.player = new Peer(f.player.id);
            f.tick(); assertEquals(0, f.rebinds);
        }
    }

    @Test void closedTransportDuringConfigurationDetachesWithoutCurrentPlayerAndOnlyResetsOnce() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain(); UUID id = f.player.id;
            f.connection = null; f.player = null;
            f.checkTransport(false); f.checkTransport(false); f.drain();
            assertNull(f.registry.sessionToken(id));
            assertEquals(1, f.resets);
        }
    }

    @Test void liveConfigurationTransportKeepsPublicStateUntilNextPlayBinding() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain(); Peer old = f.player;
            f.connection = null; f.player = null;
            f.checkTransport(true); f.drain();
            assertSame(old, f.registry.sessionToken(old.id)); assertEquals(0, f.resets);
            f.connection = new Object(); f.player = new Peer(old.id); f.join(); f.drain();
            assertEquals(1, f.joins); assertEquals(1, f.rebinds);
        }
    }

    @Test void queuedTransportCleanupCannotResetSameUuidReconnect() {
        try (Fixture f = new Fixture()) {
            f.join(); f.drain(); UUID id = f.player.id;
            f.connection = null; f.player = null; f.checkTransport(false);
            f.connection = new Object(); f.transport = new Object(); f.player = new Peer(id);
            f.join(); f.drain();
            assertSame(f.player, f.registry.sessionToken(id));
            assertEquals(2, f.joins); assertEquals(0, f.resets);
        }
    }

    @Test void transportClosedBeforeQueuedFirstJoinCannotRegisterRetiredPlayer() {
        try (Fixture f = new Fixture()) {
            f.join(); UUID id = f.player.id;
            f.connection = null; f.player = null; f.checkTransport(false); f.drain();
            assertNull(f.registry.sessionToken(id));
            assertEquals(0, f.joins); assertEquals(1, f.resets);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Object lock = new Object();
        Object connection = new Object(), transport = new Object();
        final UUID id = UUID.randomUUID();
        Peer player = new Peer(id);
        final List<Runnable> tasks = new ArrayList<>();
        final ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();
        final ClientConnectionLifecycle lifecycle = new ClientConnectionLifecycle(lock, () -> connection, () -> transport, () -> player, tasks::add);
        int joins, resets, rebinds;
        void join() { lifecycle.join(player, () -> { assertTrue(Thread.holdsLock(lock)); joins++; registry.join(player); }, this::replace); }
        void quit() { lifecycle.quit(player, connection, old -> registry.leave((Peer) old), () -> { assertTrue(Thread.holdsLock(lock)); resets++; }); }
        void tick() { lifecycle.tick(this::replace); }
        void checkTransport(boolean connected) {
            lifecycle.disconnectIfClosed(physical -> { assertSame(transport, physical); return connected; },
                    old -> registry.leave((Peer) old), () -> { assertTrue(Thread.holdsLock(lock)); resets++; });
        }
        void replace(Object old) {
            assertTrue(Thread.holdsLock(lock));
            registry.join(player); registry.leave((Peer) old);
            assertSame(player, registry.sessionToken(player.id), "Late old-player leave must preserve the atomic replacement");
            rebinds++;
        }
        void drain() { var copy = List.copyOf(tasks); tasks.clear(); copy.forEach(Runnable::run); }
        public void close() { Object current = registry.sessionToken(id); if (current instanceof IPlayerClient peer) registry.leave(peer); }
    }
    private record Peer(UUID id) implements IPlayerClient {
        public UUID getUUID() { return id; }
        public String getName() { return "lifecycle"; }
        public ClientType getClientType() { return ClientType.LOCAL; }
    }
}
