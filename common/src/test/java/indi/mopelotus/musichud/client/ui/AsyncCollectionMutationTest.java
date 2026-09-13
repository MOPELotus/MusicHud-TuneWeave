package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCollectionMutationTest {
    @Test void serializesThroughCommitAndDoesNotApplyAnOptimisticFailedWrite() {
        var worker = new ArrayList<Runnable>(); var ui = new ArrayList<Runnable>();
        var gate = new AsyncCollectionMutation(); var model = new AtomicInteger(1); var errors = new AtomicInteger();
        assertTrue(gate.submit(worker::add, ui::add, () -> true, () -> 2, model::set, e -> errors.incrementAndGet(), () -> {}));
        assertEquals(1, model.get());
        assertFalse(gate.submit(worker::add, ui::add, () -> true, () -> 3, model::set, e -> fail(), () -> {}));
        worker.removeFirst().run(); assertTrue(gate.isBusy()); assertEquals(1, model.get());
        ui.removeFirst().run(); assertFalse(gate.isBusy()); assertEquals(2, model.get());
        gate.submit(worker::add, ui::add, () -> true, () -> { throw new IllegalStateException(); }, model::set, e -> errors.incrementAndGet(), () -> {});
        worker.removeFirst().run(); ui.removeFirst().run();
        assertEquals(2, model.get()); assertEquals(1, errors.get()); assertFalse(gate.isBusy());
    }

    @Test void switchingAccountOrDetachingRejectsBothQueuedRequestAndLateCommit() {
        for (boolean beforeRequest : new boolean[]{false, true}) {
            var worker = new ArrayList<Runnable>(); var ui = new ArrayList<Runnable>();
            var current = new AtomicBoolean(true); var requests = new AtomicInteger(); var commits = new AtomicInteger();
            var settled = new AtomicInteger(); var gate = new AsyncCollectionMutation();
            gate.submit(worker::add, ui::add, current::get, requests::incrementAndGet,
                    ignored -> commits.incrementAndGet(), e -> fail(), settled::incrementAndGet);
            if (beforeRequest) current.set(false);
            worker.removeFirst().run(); current.set(false); ui.removeFirst().run();
            assertEquals(beforeRequest ? 0 : 1, requests.get()); assertEquals(0, commits.get());
            assertEquals(1, settled.get()); assertFalse(gate.isBusy());
        }
    }

    @Test void cancelledRequestCannotBecomeANullCommitAndRejectedExecutorReleasesGate() {
        var worker = new ArrayList<Runnable>(); var ui = new ArrayList<Runnable>();
        var current = new AtomicBoolean(false); var gate = new AsyncCollectionMutation();
        gate.submit(worker::add, ui::add, current::get, () -> { fail(); return 1; }, ignored -> fail(), e -> fail(), () -> {});
        worker.removeFirst().run(); current.set(true); ui.removeFirst().run(); assertFalse(gate.isBusy());
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> gate.submit(
                action -> { throw new java.util.concurrent.RejectedExecutionException(); }, ui::add,
                current::get, () -> 1, ignored -> fail(), e -> fail(), () -> {}));
        assertFalse(gate.isBusy());
        gate.submit(Runnable::run, action -> { throw new java.util.concurrent.RejectedExecutionException(); },
                current::get, () -> 1, ignored -> fail(), e -> fail(), () -> {});
        assertFalse(gate.isBusy());
    }
}
