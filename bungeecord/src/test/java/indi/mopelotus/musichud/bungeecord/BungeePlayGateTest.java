package indi.mopelotus.musichud.bungeecord;

import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayGate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BungeePlayGateTest {
    @Test void invalidByteCountsDoNotDamageAnAlreadyQueuedMessage() {
        var gate = new BungeePlayGate(); var loop = new Loop();
        Object peer = new Object(), backend = new Object(); var sent = new AtomicInteger();
        gate.submit(peer, backend, () -> true, loop, 10, sent::incrementAndGet, () -> fail("overflow"));
        for (int bytes : new int[]{-1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> gate.submit(peer, backend, () -> true,
                    loop, bytes, () -> fail("invalid delivery"), () -> fail("overflow")));
        }
        loop.ready = true; loop.runNow();
        assertEquals(1, sent.get());
        gate.close();
    }

    @Test void waitsForPlayAndPreservesPacketOrderIncludingReentrantSnapshotDelivery() {
        var gate = new BungeePlayGate(); var transport = new Loop();
        Object peer = new Object(), backend = new Object(); List<Integer> sent = new ArrayList<>();
        Runnable overflow = () -> fail("unexpected overflow");
        gate.submit(peer, backend, () -> true, transport, 5, () -> sent.add(1), overflow);
        gate.submit(peer, backend, () -> true, transport, 0,
                () -> gate.submit(peer, backend, () -> true, transport, 8, () -> sent.add(3), overflow), overflow);
        gate.submit(peer, backend, () -> true, transport, 8, () -> sent.add(2), overflow);
        assertEquals(1, transport.now.size(), "One wakeup per physical peer");
        transport.runNow(); assertTrue(sent.isEmpty()); assertEquals(1, transport.later.size());
        transport.ready = true; transport.runLater(); transport.runNow();
        assertEquals(List.of(1, 2, 3), sent);
    }
    @Test void oldBackendAndClosedLifetimeCannotSendTheirQueuedWork() {
        var gate = new BungeePlayGate(); var loop = new Loop(); Object peer = new Object();
        var sent = new ArrayList<String>();
        gate.submit(peer, new Object(), () -> true, loop, 5, () -> sent.add("old"), () -> fail("overflow"));
        loop.runNow();
        gate.submit(peer, new Object(), () -> true, loop, 5, () -> sent.add("new"), () -> fail("overflow"));
        loop.ready = true; loop.runLater(); loop.runNow();
        assertEquals(List.of("new"), sent);
        loop.ready = false;
        gate.submit(peer, new Object(), () -> true, loop, 5, () -> sent.add("closed"), () -> fail("overflow"));
        gate.close(); loop.ready = true; loop.runNow(); loop.runLater();
        assertEquals(List.of("new"), sent);
    }
    @Test void disconnectionAndInactivePeerRejectLateSends() {
        var gate = new BungeePlayGate(); var loop = new Loop(); Object peer = new Object();
        var active = new AtomicBoolean(true); var sent = new AtomicInteger();
        gate.submit(peer, new Object(), active::get, loop, 5, sent::incrementAndGet, () -> fail("overflow"));
        loop.runNow(); active.set(false); loop.ready = true; loop.runLater();
        assertEquals(0, sent.get());
        active.set(true); loop.ready = false;
        gate.submit(peer, new Object(), active::get, loop, 5, sent::incrementAndGet, () -> fail("overflow"));
        gate.cancel(peer); loop.ready = true; loop.runNow();
        assertEquals(0, sent.get());
    }
    @Test void memoryAndMessageLimitsApplyBeforeEventLoopRuns() {
        for (boolean byteLimit : List.of(false, true)) {
            var gate = new BungeePlayGate(); var loop = new Loop();
            Object peer = new Object(), backend = new Object(); var active = new AtomicBoolean(true);
            var rejected = new AtomicInteger(); var sent = new AtomicInteger();
            Runnable overflow = () -> { rejected.incrementAndGet(); active.set(false); };
            int count = byteLimit ? 3 : 129, bytes = byteLimit ? indi.mopelotus.musichud.network.PayloadFragments.MAX_BYTES : 0;
            for (int i = 0; i < count; i++) gate.submit(peer, backend, active::get, loop, bytes, sent::incrementAndGet, overflow);
            assertEquals(1, rejected.get()); loop.ready = true; loop.runNow();
            assertEquals(0, sent.get());
            gate.close();
        }
    }
    @Test void deliveryFailureClearsRemainingBatch() {
        var gate = new BungeePlayGate(); var loop = new Loop(); Object peer = new Object(), backend = new Object();
        gate.submit(peer, backend, () -> true, loop, 0, () -> { throw new IllegalStateException("send failed"); }, () -> fail("overflow"));
        gate.submit(peer, backend, () -> true, loop, 0, () -> fail("must not send after failure"), () -> fail("overflow"));
        loop.ready = true;
        assertThrows(IllegalStateException.class, loop::runNow);
        loop.runNow(); loop.runLater();
    }
    private static final class Loop implements BungeePlayGate.Transport {
        boolean ready;
        final ArrayDeque<Runnable> now = new ArrayDeque<>(), later = new ArrayDeque<>();
        public boolean ready() { return ready; }
        public void execute(Runnable task) { now.addLast(task); }
        public void later(Runnable task) { later.addLast(task); }
        void runNow() { while (!now.isEmpty()) now.removeFirst().run(); }
        void runLater() { int count = later.size(); while (count-- > 0) later.removeFirst().run(); }
    }
}
