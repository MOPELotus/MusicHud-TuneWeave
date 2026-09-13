package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.utils.ServerConnectionControlQueue;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.DisconnectMessage;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerConnectionControlQueueTest {
    @Test void disconnectReceivedBeforePendingHandshakeCompletesStillRetiresThatMembership() {
        List<Runnable> workers = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add);
        var registry = ServerPlayerRegistry.getInstance();
        var player = peer(UUID.randomUUID(), new Object());
        NetworkReceiver<ConnectRequest> connect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, origin) -> registry.join(origin));
        NetworkReceiver<DisconnectMessage> disconnect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, origin) -> registry.leave(origin));
        try {
            connect.receive(ConnectRequest.current(), player);
            disconnect.receive(DisconnectMessage.INSTANCE, player);
            assertNull(registry.sessionToken(player.getUUID()));
            assertEquals(1, workers.size());
            workers.getFirst().run();
            assertNull(registry.sessionToken(player.getUUID()), "A delayed Connect must not undo its later Disconnect");
        } finally { registry.leave(player); }
    }

    @Test void disconnectedOldControlCannotJoinOverReplacementWithTheSameUuid() {
        List<Runnable> workers = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add);
        var registry = ServerPlayerRegistry.getInstance();
        UUID id = UUID.randomUUID();
        var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        IPlayerClient old = new IPlayerClient() {
            public UUID getUUID() { return id; }
            public String getName() { return "old"; }
            public ClientType getClientType() { return ClientType.REMOTE; }
            public boolean isConnected() { return connected.get(); }
        };
        IPlayerClient replacement = peer(id, new Object());
        NetworkReceiver<ConnectRequest> connect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, origin) -> registry.join(origin));
        try {
            connect.receive(ConnectRequest.current(), old);
            connected.set(false);
            connect.receive(ConnectRequest.current(), replacement);
            assertEquals(2, workers.size());
            workers.removeLast().run();
            workers.removeLast().run();
            assertSame(replacement, registry.sessionToken(id));
        } finally { registry.leave(replacement); }
    }

    @Test void failedControlActionDoesNotPreventTheLaterDisconnect() {
        List<Runnable> workers = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add);
        var registry = ServerPlayerRegistry.getInstance();
        var player = peer(UUID.randomUUID(), new Object());
        NetworkReceiver<ConnectRequest> connect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, origin) -> { throw new IllegalStateException("controlled handshake failure"); });
        NetworkReceiver<DisconnectMessage> disconnect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, origin) -> registry.leave(origin));
        try {
            registry.join(player);
            connect.receive(ConnectRequest.current(), player);
            disconnect.receive(DisconnectMessage.INSTANCE, player);
            assertDoesNotThrow(workers.getFirst()::run);
            assertNull(registry.sessionToken(player.getUUID()));
        } finally { registry.leave(player); }
    }

    @Test void respawnedPlayerWrappersShareControlOrderButKeepDistinctMembershipIdentity() {
        List<Runnable> workers = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add);
        Object transport = new Object(); UUID id = UUID.randomUUID();
        IPlayerClient old = peer(id, transport), replacement = peer(id, transport);
        var registry = ServerPlayerRegistry.getInstance();
        NetworkReceiver<ConnectRequest> connect = ServerDataPacketVThreadExecutor.controlReceiver(queue,
                (request, player) -> registry.join(player));
        try {
            connect.receive(ConnectRequest.current(), old);
            connect.receive(ConnectRequest.current(), replacement);
            assertEquals(1, workers.size()); workers.getFirst().run();
            assertSame(replacement, registry.sessionToken(id));
            registry.leave(old);
            assertSame(replacement, registry.sessionToken(id), "Old entity detach must not remove the respawned member");
        } finally { registry.leave(replacement); }
    }

    private static IPlayerClient peer(UUID id, Object transport) {
        return new IPlayerClient() {
            public UUID getUUID() { return id; }
            public String getName() { return "respawn"; }
            public ClientType getClientType() { return ClientType.REMOTE; }
            public Object controlConnectionIdentity() { return transport; }
        };
    }

    @Test void perPhysicalConnectionFifoIsIndependentOfWorkerExecutionOrderAndReleasesIdlePeers() {
        List<Runnable> workers = new ArrayList<>(); List<Integer> effects = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add);
        Object first = new String("equal"), second = new String("equal");
        queue.execute(first, () -> effects.add(1)); queue.execute(second, () -> effects.add(3));
        queue.execute(first, () -> effects.add(2));
        assertEquals(2, workers.size());
        workers.removeLast().run(); workers.removeLast().run();
        assertEquals(List.of(3, 1, 2), effects);
        queue.execute(first, () -> effects.add(4));
        assertEquals(1, workers.size(), "Idle mailbox is released and a new worker is scheduled");
        workers.removeLast().run(); assertEquals(List.of(3, 1, 2, 4), effects);
    }

    @Test void abusivePendingControlsStayBoundedAndPreserveTheFinalMembershipIntent() {
        List<Runnable> workers = new ArrayList<>(); List<Integer> effects = new ArrayList<>();
        var queue = new ServerConnectionControlQueue(workers::add); Object peer = new Object();
        for (int i = 0; i < 10_001; i++) { int intent = i; queue.execute(peer, () -> effects.add(intent)); }
        assertEquals(1, workers.size()); workers.getFirst().run();
        assertTrue(effects.size() <= 64); assertEquals(10_000, effects.getLast());
    }

    @Test void concurrentEnqueueDuringActiveConnectCannotRunDisconnectFirst() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (executor) {
            var queue = new ServerConnectionControlQueue(executor);
            Object peer = new Object(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            List<String> effects = Collections.synchronizedList(new ArrayList<>());
            queue.execute(peer, () -> { started.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } effects.add("join"); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.execute(peer, () -> { effects.add("leave"); done.countDown(); });
            assertEquals(1, done.getCount()); release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(List.of("join", "leave"), effects);
        }
    }
}
