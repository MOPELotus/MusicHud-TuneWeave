package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;
import indi.mopelotus.musichud.utils.ServerOrderedPacketQueue;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerOrderedPacketQueueTest {
    record Packet(int value) implements IPayload {}
    static class Peer implements IPlayerClient {
        final UUID id;
        final Object transport;
        boolean connected = true;
        Peer(UUID id, Object transport) { this.id = id; this.transport = transport; }
        public UUID getUUID() { return id; }
        public String getName() { return "queue-test"; }
        public ClientType getClientType() { return ClientType.REMOTE; }
        public Object controlConnectionIdentity() { return transport; }
        public boolean isConnected() { return connected; }
    }

    @Test void batchOrderSurvivesReversedWorkerSchedulingAndOneBadPacket() {
        List<Runnable> workers = new ArrayList<>(); List<Integer> effects = new ArrayList<>();
        var queue = new ServerOrderedPacketQueue(workers::add);
        var first = new Peer(UUID.randomUUID(), new Object());
        var second = new Peer(UUID.randomUUID(), new Object());
        var registry = ServerPlayerRegistry.getInstance(); registry.join(first); registry.join(second);
        NetworkReceiver<Packet> receiver = ServerDataPacketVThreadExecutor.orderedReceiver(queue, (packet, peer) -> {
            if (packet.value() == -1) throw new IllegalArgumentException("invalid queue item");
            effects.add(packet.value());
        });
        try {
            for (int i = 0; i < 28; i++) receiver.receive(new Packet(i), first);
            receiver.receive(new Packet(-1), first); receiver.receive(new Packet(28), first);
            receiver.receive(new Packet(100), second);
            assertEquals(2, workers.size());
            workers.removeLast().run(); workers.removeLast().run();
            var expected = new ArrayList<Integer>(); expected.add(100);
            for (int i = 0; i <= 28; i++) expected.add(i);
            assertEquals(expected, effects);
            receiver.receive(new Packet(29), first);
            assertEquals(1, workers.size(), "drained peers must release their worker slot");
            workers.removeLast().run(); assertEquals(29, effects.getLast());
        } finally { registry.leave(first); registry.leave(second); }
    }

    @Test void delayedPacketsCannotCrossLeaveRejoinOrPhysicalDisconnect() {
        List<Runnable> workers = new ArrayList<>(); List<Integer> effects = new ArrayList<>();
        var queue = new ServerOrderedPacketQueue(workers::add);
        UUID id = UUID.randomUUID(); Object transport = new Object();
        var old = new Peer(id, transport); var replacement = new Peer(id, transport);
        var registry = ServerPlayerRegistry.getInstance();
        NetworkReceiver<Packet> receiver = ServerDataPacketVThreadExecutor.orderedReceiver(queue,
                (packet, peer) -> effects.add(packet.value()));
        receiver.receive(new Packet(-1), old);
        assertTrue(workers.isEmpty(), "nonmembers cannot enqueue work");
        registry.join(old);
        try {
            receiver.receive(new Packet(1), old);
            registry.leave(old); registry.join(replacement);
            receiver.receive(new Packet(2), replacement);
            registry.leave(old);
            workers.removeLast().run(); assertEquals(List.of(2), effects);
            receiver.receive(new Packet(3), replacement); replacement.connected = false;
            workers.removeLast().run(); assertEquals(List.of(2), effects);
        } finally { registry.leave(replacement); registry.leave(old); }
    }

    @Test void overloadRejectsNewestWithoutLosingAcceptedWorkAndSubmissionCanRecover() {
        List<Runnable> workers = new ArrayList<>(); List<Integer> effects = new ArrayList<>();
        var queue = new ServerOrderedPacketQueue(workers::add); Object peer = new Object();
        for (int i = 0; i < 4096; i++) { int value = i; assertTrue(queue.execute(peer, () -> effects.add(value))); }
        assertFalse(queue.execute(peer, () -> fail("rejected work must not run")));
        workers.removeLast().run(); assertEquals(4096, effects.size());
        for (int i = 0; i < effects.size(); i++) assertEquals(i, effects.get(i));
        assertTrue(queue.execute(peer, () -> effects.add(4096))); workers.removeLast().run();
        assertEquals(4096, effects.getLast());
        var rejecting = new ServerOrderedPacketQueue(action -> { throw new RejectedExecutionException(); });
        assertThrows(RejectedExecutionException.class, () -> rejecting.execute(peer, () -> {}));
        assertThrows(RejectedExecutionException.class, () -> rejecting.execute(peer, () -> {}));
    }

    @Test void requestsArrivingDuringActiveWorkStayBehindIt() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var queue = new ServerOrderedPacketQueue(executor); Object peer = new Object();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
            List<Integer> effects = Collections.synchronizedList(new ArrayList<>());
            queue.execute(peer, () -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); } effects.add(1); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.execute(peer, () -> { effects.add(2); done.countDown(); });
            assertEquals(1, done.getCount()); release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(List.of(1, 2), effects);
        }
    }
}
