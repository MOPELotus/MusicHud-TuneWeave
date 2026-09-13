package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListenerDispatcherTest {
    @Test
    void continuesAfterListenerFailure() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Consumer<String> failing = ignored -> {
            calls.incrementAndGet();
            throw new IllegalStateException("expected");
        };
        Consumer<String> succeeding = ignored -> calls.incrementAndGet();

        ListenerDispatcher.dispatch(List.of(failing, succeeding),
                listener -> listener.accept("state"),
                ignored -> failures.incrementAndGet());

        assertEquals(2, calls.get());
        assertEquals(1, failures.get());
    }

    @Test
    void snapshotsListenersBeforeDispatch() {
        List<Runnable> listeners = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        listeners.add(listeners::clear);
        listeners.add(calls::incrementAndGet);

        ListenerDispatcher.dispatch(listeners, Runnable::run, ignored -> {
        });

        assertEquals(1, calls.get());
    }
}
