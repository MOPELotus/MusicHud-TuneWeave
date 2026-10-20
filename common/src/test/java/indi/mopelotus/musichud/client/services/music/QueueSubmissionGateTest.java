package indi.mopelotus.musichud.client.services.music;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static indi.mopelotus.musichud.client.services.music.QueueSubmissionGate.Result.*;

class QueueSubmissionGateTest {
    private static class Fixture {
        final Object owner = new Object();
        final ArrayList<Runnable> client = new ArrayList<>();
        final AtomicReference<QueueSubmissionGate.Scope> scope = new AtomicReference<>(
                new QueueSubmissionGate.Scope(new Object(), new Object(), 1, new Object()));
        final AtomicBoolean ready = new AtomicBoolean(true);
        final AtomicInteger sends = new AtomicInteger();
        final QueueSubmissionGate gate = new QueueSubmissionGate(owner, client::add, scope::get, ready::get);
        void drain() { while (!client.isEmpty()) client.removeFirst().run(); }
    }

    @Test void finalClientReadinessRejectsTransientPlayerGapAndDoesNotReplayAfterRecovery() {
        var f = new Fixture(); var old = f.scope.get();
        f.scope.set(new QueueSubmissionGate.Scope(old.connection(), null, old.generation(), old.account()));
        var request = f.gate.submit(f.sends::incrementAndGet);
        f.scope.set(old); f.drain();
        assertEquals(REJECTED, request.result().join()); assertEquals(0, f.sends.get());
        var fresh = f.gate.submit(f.sends::incrementAndGet); f.drain();
        assertEquals(SUBMITTED, fresh.result().join()); assertEquals(1, f.sends.get());
    }

    @Test void queuedActionsCannotCrossDisconnectPlayerReplacementGenerationOrAccount() {
        for (int changed = 0; changed < 4; changed++) {
            var f = new Fixture(); var old = f.scope.get(); var request = f.gate.submit(f.sends::incrementAndGet);
            f.scope.set(new QueueSubmissionGate.Scope(changed == 0 ? new Object() : old.connection(),
                    changed == 1 ? new Object() : old.player(), changed == 2 ? 2 : old.generation(),
                    changed == 3 ? new Object() : old.account()));
            f.drain(); assertEquals(REJECTED, request.result().join()); assertEquals(0, f.sends.get());
        }
    }

    @Test void channelModeOrClosedTransportRejectsAndRapidClicksEachSubmitAtMostOnce() {
        var f = new Fixture(); var rejected = f.gate.submit(f.sends::incrementAndGet);
        f.ready.set(false); f.drain(); assertEquals(REJECTED, rejected.result().join());
        f.ready.set(true); var a = f.gate.submit(f.sends::incrementAndGet); var b = f.gate.submit(f.sends::incrementAndGet);
        f.drain(); f.drain(); assertEquals(SUBMITTED, a.result().join()); assertEquals(SUBMITTED, b.result().join());
        assertEquals(2, f.sends.get());
    }

    @Test void latePreparedSelectionIsRejectedWhenItsOriginalActionScopeExpires() {
        var f = new Fixture(); var preparedAccount = new AtomicBoolean(true);
        var gate = new QueueSubmissionGate(f.owner, f.client::add, f.scope::get,
                () -> f.ready.get() && preparedAccount.get());
        var request = gate.submit(f.sends::incrementAndGet);
        preparedAccount.set(false); f.drain();
        assertEquals(REJECTED, request.result().join()); assertEquals(0, f.sends.get());
    }

    @Test void callerScopeChangingBeforeServiceCaptureCannotRetargetPreparedSelection() {
        var f = new Fixture(); var originalAction = f.scope.get();
        var gate = new QueueSubmissionGate(f.owner, f.client::add, f.scope::get,
                () -> originalAction.same(f.scope.get()) && f.ready.get());
        // The click/provider completion already belongs to the old account and listener.
        f.scope.set(new QueueSubmissionGate.Scope(new Object(), new Object(), 2, new Object()));
        var request = gate.submit(f.sends::incrementAndGet);
        f.drain(); assertEquals(REJECTED, request.result().join()); assertEquals(0, f.sends.get());
    }

    @Test void modeMutationCannotChangeTheRouteBetweenFinalReadinessAndSend() throws Exception {
        var f = new Fixture(); var route = new AtomicReference<>("REMOTE");
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var changed = new java.util.concurrent.CompletableFuture<Void>();
        var observed = new ArrayList<String>();
        var gate = new QueueSubmissionGate(f.owner, f.client::add, f.scope::get, () -> {
            assertTrue(Thread.holdsLock(f.owner));
            Thread.ofVirtual().start(() -> {
                attempted.countDown();
                synchronized (f.owner) {
                    route.set("LOCAL"); var old = f.scope.get();
                    f.scope.set(new QueueSubmissionGate.Scope(old.connection(), old.player(), 2, old.account()));
                }
                changed.complete(null);
            });
            try { assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return true;
        });
        var request = gate.submit(() -> {
            assertTrue(Thread.holdsLock(f.owner)); observed.add(route.get());
        });
        f.drain(); changed.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(SUBMITTED, request.result().join()); assertEquals(java.util.List.of("REMOTE"), observed);
        assertEquals("LOCAL", route.get());
    }

    @Test void submissionIsQueuedAndCompletionCallbacksRunOutsideConnectionOwner() {
        var f = new Fixture(); var request = f.gate.submit(f.sends::incrementAndGet);
        assertEquals(0, f.sends.get()); assertFalse(request.result().isDone());
        var callbacks = new AtomicInteger();
        request.result().thenRun(() -> {
            assertFalse(Thread.holdsLock(f.owner)); callbacks.incrementAndGet();
        });
        f.drain(); assertEquals(1, f.sends.get()); assertEquals(1, callbacks.get());
    }

    @Test void transportAndExecutorFailuresCompleteWithoutInventingSuccess() {
        var f = new Fixture(); var failed = f.gate.submit(() -> { throw new IllegalStateException(); }); f.drain();
        assertTrue(failed.result().isCompletedExceptionally());
        var stopped = new QueueSubmissionGate(f.owner, task -> { throw new RejectedExecutionException(); }, f.scope::get, () -> true);
        assertTrue(stopped.submit(f.sends::incrementAndGet).result().isCompletedExceptionally());
        assertEquals(0, f.sends.get());
    }
}
