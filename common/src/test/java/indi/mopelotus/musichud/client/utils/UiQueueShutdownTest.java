package indi.mopelotus.musichud.client.utils;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UiQueueShutdownTest {
    @Test void dueAnimationCannotRescheduleWhileQueueDrains() {
        List<Runnable> callbacks = new ArrayList<>();
        AtomicInteger latePosts = new AtomicInteger();
        callbacks.add(latePosts::incrementAndGet);
        Runnable finish = UiQueueShutdown.wrap(callbacks::clear,
                () -> List.copyOf(callbacks).forEach(Runnable::run));
        finish.run();
        callbacks.add(latePosts::incrementAndGet);
        finish.run(); // A stale second invocation must not restart or modify the stopped queue.
        assertEquals(0, latePosts.get());
        assertEquals(1, callbacks.size());
    }

    @Test void queueStillQuitsIfFrameCleanupFails() {
        AtomicInteger quits = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("cleanup");
        Runnable finish = UiQueueShutdown.wrap(() -> { throw failure; }, quits::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, finish::run));
        finish.run();
        assertEquals(1, quits.get());
    }
}
