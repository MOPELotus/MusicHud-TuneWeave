package indi.mopelotus.musichud.client.services.cloud;

import indi.mopelotus.musichud.client.ui.dto.CloudEntryState;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.*;

class CloudUploadServiceTest {
    @Test void tasksAreSequentialAndSurviveAnUnsubscribedPage() {
        var executor = new ManualExecutor();
        var scope = new AtomicReference<>(new Object());
        List<String> uploaded = new ArrayList<>();
        var queue = queue(executor, scope, task -> (progress, publishing, cancelled) -> {
            uploaded.add(task.getFileName());
            progress.accept(42);
            publishing.run();
            return "netease:" + task.getFileName();
        });
        AtomicInteger updates = new AtomicInteger();
        var listener = queue.addOnChange(updates::incrementAndGet);
        queue.enqueue(Path.of("one.mp3"));
        queue.enqueue(Path.of("two.mp3"));
        assertEquals(1, executor.pending.size());
        listener.unregister();
        int priorUpdates = updates.get();
        executor.drain();
        assertEquals(List.of("one.mp3", "two.mp3"), uploaded);
        assertEquals(priorUpdates, updates.get());
        assertTrue(queue.snapshot().stream().allMatch(task -> task.getState() == CloudEntryState.COMPLETED));
    }

    @Test void changingAccountsInvalidatesQueuedWorkInsteadOfAdoptingTheNewAccount() {
        var executor = new ManualExecutor();
        var scope = new AtomicReference<>(new Object());
        List<String> uploaded = new ArrayList<>();
        var queue = queue(executor, scope, task -> (progress, publishing, cancelled) -> {
            uploaded.add(task.getFileName()); return "done";
        });
        queue.enqueue(Path.of("old.mp3"));
        scope.set(new Object());
        queue.enqueue(Path.of("new.mp3"));
        executor.drain();
        assertEquals(List.of("new.mp3"), uploaded);
        assertEquals(1, queue.snapshot().size());
    }

    @Test void cancelThenImmediateRetryRejectsOldProgressAndCompletion() {
        var executor = new ManualExecutor();
        var scope = new AtomicReference<>(new Object());
        var holder = new AtomicReference<CloudUploadService>();
        var oldProgress = new AtomicReference<LongConsumer>();
        AtomicInteger attempts = new AtomicInteger();
        var queue = queue(executor, scope, task -> (progress, publishing, cancelled) -> {
            if (attempts.incrementAndGet() == 1) {
                oldProgress.set(progress);
                holder.get().cancel(task.getId());
                holder.get().retry(task.getId());
                assertTrue(cancelled.getAsBoolean());
                return "stale-reference";
            }
            progress.accept(42);
            oldProgress.get().accept(999);
            assertEquals(42, task.getBytesUploaded());
            publishing.run();
            return "new-reference";
        });
        holder.set(queue);
        var task = queue.enqueue(Path.of("one.mp3"));
        executor.drain();
        assertEquals(2, attempts.get());
        assertEquals(CloudEntryState.COMPLETED, task.getState());
        assertEquals("new-reference", task.getResolvedTrackId());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void removingActiveWorkCannotResurrectItWhenTheTransferFinishesLate() {
        var executor = new ManualExecutor();
        var holder = new AtomicReference<CloudUploadService>();
        var queue = queue(executor, new AtomicReference<>(new Object()), task -> (progress, publishing, cancelled) -> {
            holder.get().remove(task.getId());
            progress.accept(100);
            assertTrue(cancelled.getAsBoolean());
            return "late-reference";
        });
        holder.set(queue);
        queue.enqueue(Path.of("one.mp3"));
        executor.drain();
        assertTrue(queue.snapshot().isEmpty());
    }

    @Test void publishingIsNotReportedCancelledAfterTheIrreversibleRequestBegins() {
        var executor = new ManualExecutor();
        var holder = new AtomicReference<CloudUploadService>();
        var queue = queue(executor, new AtomicReference<>(new Object()), task -> (progress, publishing, cancelled) -> {
            publishing.run();
            holder.get().cancel(task.getId());
            holder.get().remove(task.getId());
            assertFalse(cancelled.getAsBoolean());
            return "published";
        });
        holder.set(queue);
        var task = queue.enqueue(Path.of("one.mp3"));
        executor.drain();
        assertEquals(CloudEntryState.COMPLETED, task.getState());
        queue.reconcileReferences(Set.of("published"));
        assertEquals(1, queue.snapshot().size());
        queue.markPresented(task.getId());
        queue.reconcileReferences(Set.of("published"));
        assertTrue(queue.snapshot().isEmpty());
    }

    @Test void failedUploadDoesNotStrandTheNextQueuedFile() {
        var executor = new ManualExecutor();
        var queue = queue(executor, new AtomicReference<>(new Object()), task -> (progress, publishing, cancelled) -> {
            if (task.getFileName().equals("bad.mp3")) throw new IllegalArgumentException("bad file");
            return "ok";
        });
        var bad = queue.enqueue(Path.of("bad.mp3"));
        var good = queue.enqueue(Path.of("good.mp3"));
        executor.drain();
        assertEquals(CloudEntryState.FAILED, bad.getState());
        assertEquals(CloudEntryState.COMPLETED, good.getState());
    }

    @Test void workerRejectionAllowsTheUserToRetryLater() {
        var manual = new ManualExecutor();
        AtomicBoolean reject = new AtomicBoolean(true);
        Executor executor = work -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException();
            manual.execute(work);
        };
        var queue = queue(executor, new AtomicReference<>(new Object()), task -> (progress, publishing, cancelled) -> "ok");
        var task = queue.enqueue(Path.of("one.mp3"));
        assertEquals(CloudEntryState.FAILED, task.getState());
        reject.set(false);
        queue.retry(task.getId());
        manual.drain();
        assertEquals(CloudEntryState.COMPLETED, task.getState());
    }

    private static CloudUploadService queue(Executor executor, AtomicReference<Object> scope,
                                            CloudUploadService.Preparation preparation) {
        return new CloudUploadService(executor, scope::get, preparation, task -> {
            task.setFileSize(100);
            task.setPrepared(true);
        });
    }

    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> pending = new ArrayDeque<>();
        public void execute(Runnable task) { pending.add(task); }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
}
