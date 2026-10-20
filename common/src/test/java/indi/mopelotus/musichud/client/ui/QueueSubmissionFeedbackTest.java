package indi.mopelotus.musichud.client.ui;

import indi.mopelotus.musichud.client.services.music.QueueSubmissionGate;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static indi.mopelotus.musichud.client.services.music.QueueSubmissionGate.Result.*;

class QueueSubmissionFeedbackTest {
    @Test void rejectedConnectionStillReportsFailureOnCurrentViewAndNeverSuccess() {
        var ui = new ArrayList<Runnable>(); var received = new ArrayList<QueueSubmissionGate.Result>();
        var future = new CompletableFuture<QueueSubmissionGate.Result>(); var settled = new AtomicInteger();
        QueueSubmissionFeedback.observe(new QueueSubmissionGate.Submission(future, () -> false), ui::add,
                () -> true, received::add, settled::incrementAndGet);
        future.complete(REJECTED); ui.removeFirst().run();
        assertEquals(java.util.List.of(REJECTED), received); assertEquals(1, settled.get());
    }

    @Test void closingPageChangingAccountOrLeavingBeforeUiCompletionSuppressesLateFeedback() {
        for (var outcome : QueueSubmissionGate.Result.values()) {
            var ui = new ArrayList<Runnable>(); var visible = new AtomicBoolean(true); var settled = new AtomicInteger();
            var future = new CompletableFuture<QueueSubmissionGate.Result>();
            QueueSubmissionFeedback.observe(new QueueSubmissionGate.Submission(future, () -> true), ui::add,
                    visible::get, ignored -> fail("late feedback"), settled::incrementAndGet);
            future.complete(outcome); visible.set(false); ui.removeFirst().run(); assertEquals(1, settled.get());
        }
    }

    @Test void feedbackSinkCompletesInsideTheGuardedUiTurnWithoutAnotherQueuedTurn() {
        var ui = new ArrayList<Runnable>(); var visible = new AtomicBoolean(true);
        var future = new CompletableFuture<QueueSubmissionGate.Result>(); var shown = new AtomicInteger();
        QueueSubmissionFeedback.observe(new QueueSubmissionGate.Submission(future, () -> true), ui::add,
                visible::get, ignored -> shown.incrementAndGet(), () -> {});
        future.complete(SUBMITTED); assertEquals(0, shown.get());
        ui.removeFirst().run();
        assertEquals(1, shown.get()); assertTrue(ui.isEmpty());
        visible.set(false); assertEquals(1, shown.get());
    }

    @Test void lateSuccessAfterConnectionReplacementIsNotReportedAsCurrentAndErrorsAreRejected() {
        var ui = new ArrayList<Runnable>(); var received = new ArrayList<QueueSubmissionGate.Result>();
        var valid = new AtomicBoolean(true); var first = new CompletableFuture<QueueSubmissionGate.Result>();
        QueueSubmissionFeedback.observe(new QueueSubmissionGate.Submission(first, valid::get), ui::add,
                () -> true, received::add, () -> {});
        first.complete(SUBMITTED); valid.set(false); ui.removeFirst().run(); assertTrue(received.isEmpty());
        var failed = new CompletableFuture<QueueSubmissionGate.Result>();
        QueueSubmissionFeedback.observe(new QueueSubmissionGate.Submission(failed, valid::get), ui::add,
                () -> true, received::add, () -> {});
        failed.completeExceptionally(new IllegalStateException()); ui.removeFirst().run();
        assertEquals(java.util.List.of(REJECTED), received);
    }
}
