package indi.mopelotus.musichud.network;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DeferredPluginSendTest {
    @Test void closesOverLifetimeAndSendsOnceWhenRegistrationArrives() {
        var active = new AtomicBoolean(true); var ready = new AtomicBoolean(); var sent = new AtomicInteger();
        var queue = new ArrayDeque<Runnable>();
        var delivery = new DeferredPluginSend(active::get, ready::get, sent::incrementAndGet, queue::add, 40);
        delivery.run(); assertEquals(0, sent.get()); assertEquals(1, queue.size());
        ready.set(true); queue.removeFirst().run(); delivery.run(); assertEquals(1, sent.get());
        var late = new DeferredPluginSend(active::get, () -> false, sent::incrementAndGet, queue::add, 40);
        late.run(); active.set(false); queue.removeFirst().run(); assertEquals(1, sent.get()); assertTrue(queue.isEmpty());
    }

    @Test void missingRegistrationRetriesAreBounded() {
        var queue = new ArrayDeque<Runnable>(); var scheduled = new AtomicInteger();
        var delivery = new DeferredPluginSend(() -> true, () -> false, () -> fail("Not registered"),
                next -> { scheduled.incrementAndGet(); queue.add(next); }, 3);
        delivery.run(); while (!queue.isEmpty()) queue.removeFirst().run();
        assertEquals(3, scheduled.get()); delivery.run(); assertEquals(3, scheduled.get());
    }
}
