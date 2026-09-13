package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ScopedViewTasksTest {
    static final class Fixture {
        final List<Runnable> worker = new ArrayList<>(), ui = new ArrayList<>();
        final AtomicReference<Object> account = new AtomicReference<>(new Object());
        final List<String> rendered = new ArrayList<>();
        final AtomicInteger requests = new AtomicInteger();
        final ScopedViewTasks tasks = new ScopedViewTasks(worker::add, ui::add, account::get, new ScopedViewTasks.Prepare() {
            public <T> Supplier<T> capture(Supplier<T> request) {
                Object scope = account.get();
                return () -> { assertSame(scope, account.get(), "No new account adoption"); requests.incrementAndGet(); return request.get(); };
            }
        });
        Fixture() { tasks.attach(); }
        void ready() { tasks.<String>load(progress -> "ready", rendered::add, error -> fail()); worker.removeFirst().run(); drain(); }
        void drain() { while (!ui.isEmpty()) ui.removeFirst().run(); }
    }
    @Test void progressiveCallbacksAndFinalResultCannotCrossDetachOrRefresh() {
        var f = new Fixture();
        f.tasks.<String>load(progress -> { progress.accept("partial"); return "complete"; }, f.rendered::add, error -> fail());
        f.worker.removeFirst().run();
        f.ui.removeFirst().run(); assertEquals(List.of("partial"), f.rendered); assertFalse(f.tasks.canMutate());
        f.tasks.detach(); f.tasks.attach(); f.drain(); assertEquals(List.of("partial"), f.rendered);
        f.ready(); assertTrue(f.tasks.canMutate());
    }
    @Test void queuedRequestCannotAdoptNewAccountAndOldDialogCannotMutateNewPage() {
        var f = new Fixture(); f.ready(); var oldDialog = f.tasks.capture();
        f.tasks.<String>load(progress -> "new", f.rendered::add, error -> fail());
        f.account.set(new Object()); f.worker.removeFirst().run(); f.drain();
        assertEquals(1, f.requests.get()); assertFalse(f.tasks.canMutate());
        f.ready();
        assertFalse(f.tasks.mutate(oldDialog, () -> fail(), () -> fail(), error -> fail()));
    }
    @Test void mutationsAreSingleFlightAndLateSuccessCannotRefreshNewPage() {
        var f = new Fixture(); f.ready(); var token = f.tasks.capture();
        assertTrue(f.tasks.mutate(token, () -> {}, () -> f.rendered.add("saved"), error -> fail()));
        assertFalse(f.tasks.mutate(token, () -> fail(), () -> fail(), error -> fail()));
        f.worker.removeFirst().run(); f.tasks.detach(); f.drain(); assertEquals(List.of("ready"), f.rendered);
        f.tasks.attach(); f.ready(); assertTrue(f.tasks.canMutate());
    }
    @Test void failureRetainsPartialResultForRetryAndNeverEnablesWholeListMutations() {
        var f = new Fixture();
        f.tasks.<String>load(progress -> { progress.accept("first"); throw new IllegalStateException("offline"); }, f.rendered::add, error -> {});
        f.worker.removeFirst().run(); f.drain();
        assertEquals(List.of("first"), f.rendered); assertTrue(f.tasks.failed()); assertFalse(f.tasks.canMutate());
        f.ready(); assertFalse(f.tasks.failed());
    }

    @Test void olderPopupCannotReplaceNewerSelection() {
        var f = new Fixture(); f.ready();
        f.tasks.read(() -> "old popup", f.rendered::add, error -> fail());
        f.worker.removeFirst().run();
        f.tasks.read(() -> "new popup", f.rendered::add, error -> fail());
        f.worker.removeFirst().run(); f.drain();
        assertEquals(List.of("ready", "new popup"), f.rendered);
    }

    @Test void rapidHistoryTabChangesDropQueuedReadsLateSuccessAndLateFailure() {
        var f = new Fixture();
        f.tasks.<String>load(progress -> "tracks", f.rendered::add, error -> fail());
        f.worker.removeFirst().run(); // Completed request, UI delivery is still queued.
        f.tasks.<String>load(progress -> { throw new IllegalStateException("old albums failed"); }, f.rendered::add, error -> fail());
        f.worker.removeFirst().run();
        f.tasks.<String>load(progress -> fail("Superseded history request must not run"), f.rendered::add, error -> fail());
        f.tasks.<String>load(progress -> "playlists", f.rendered::add, error -> fail());
        f.worker.removeFirst().run(); f.worker.removeFirst().run(); f.drain();
        assertEquals(List.of("playlists"), f.rendered);
        assertTrue(f.tasks.canMutate());
    }

    @Test void historyAccountChangeOrLateDetachCannotPublishIntoAnotherLifetime() {
        var f = new Fixture();
        f.tasks.<String>load(progress -> "private account A", f.rendered::add, error -> fail());
        f.worker.removeFirst().run(); f.account.set(new Object()); f.drain();
        assertTrue(f.rendered.isEmpty());
        f.tasks.attach();
        f.tasks.<String>load(progress -> "private account B", f.rendered::add, error -> fail());
        f.worker.removeFirst().run(); f.tasks.detach(); f.tasks.attach(); f.drain();
        assertTrue(f.rendered.isEmpty());
        f.ready(); assertEquals(List.of("ready"), f.rendered);
    }
}
