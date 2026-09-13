package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncToggleControllerTest {
    @Test void serializesWritesAndFailureRollsBackToConfirmedState() {
        var controller = new AsyncToggleController(Runnable::run);
        var writes = new ArrayList<Boolean>(); var results = new ArrayList<CompletableFuture<Void>>();
        var checked = new AtomicBoolean(); var errors = new AtomicInteger();
        controller.bind(() -> CompletableFuture.completedFuture(false), selected -> {
            writes.add(selected); var future = new CompletableFuture<Void>(); results.add(future); return future;
        }, (value, enabled) -> checked.set(value), error -> errors.incrementAndGet());
        controller.request(true); controller.request(false);
        assertEquals(List.of(true), writes);
        results.getFirst().complete(null); assertEquals(List.of(true, false), writes);
        results.getLast().completeExceptionally(new IllegalStateException("offline"));
        assertTrue(checked.get()); assertEquals(1, errors.get());
    }

    @Test void rebindAndDetachIgnoreLateReadAndWriteCallbacks() {
        var queue = new ArrayDeque<Runnable>(); var checked = new AtomicBoolean();
        var controller = new AsyncToggleController(queue::add);
        var old = new CompletableFuture<Boolean>();
        controller.bind(() -> old, value -> CompletableFuture.completedFuture(null), (value, enabled) -> checked.set(value), error -> fail());
        old.complete(true);
        controller.bind(() -> CompletableFuture.completedFuture(false), value -> CompletableFuture.completedFuture(null),
                (value, enabled) -> checked.set(value), error -> fail());
        while (!queue.isEmpty()) queue.removeFirst().run(); assertFalse(checked.get());
        controller.request(true); controller.unbind(); checked.set(false);
        while (!queue.isEmpty()) queue.removeFirst().run(); assertFalse(checked.get());
    }

    @Test void initialReadFailureCanBeRetriedWithoutIssuingMutation() {
        var controller = new AsyncToggleController(Runnable::run); var reads = new AtomicInteger(); var writes = new AtomicInteger();
        controller.bind(() -> reads.incrementAndGet() == 1 ? CompletableFuture.failedFuture(new IllegalStateException())
                        : CompletableFuture.completedFuture(true), value -> { writes.incrementAndGet(); return CompletableFuture.completedFuture(null); },
                (value, enabled) -> {}, error -> {});
        controller.request(true); assertEquals(2, reads.get()); assertEquals(0, writes.get()); assertTrue(controller.initialized());
    }

    @Test void reentrantInvalidationDuringRenderingCannotStartOldReadOrWrite() {
        var controller = new AsyncToggleController(Runnable::run); var calls = new AtomicInteger();
        controller.bind(() -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(false); },
                selected -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); },
                (selected, enabled) -> controller.unbind(), error -> fail());
        assertEquals(0, calls.get());
        controller.bind(() -> CompletableFuture.completedFuture(false), selected -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); },
                (selected, enabled) -> { if (selected) controller.unbind(); }, error -> fail());
        controller.request(true); assertEquals(0, calls.get());
    }
}
