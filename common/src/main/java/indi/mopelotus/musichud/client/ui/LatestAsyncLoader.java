package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.*;
import java.util.function.*;

/** Publishes only the latest request, with bounded retries and cancellable queued publications. */
public final class LatestAsyncLoader<T> {
    private final Executor publishExecutor;
    private final Consumer<Runnable> retryLater;
    private final int maxAttempts;
    private long generation;

    public LatestAsyncLoader(Executor publishExecutor, Consumer<Runnable> retryLater, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Invalid retry bound");
        this.publishExecutor = publishExecutor; this.retryLater = retryLater; this.maxAttempts = maxAttempts;
    }

    public void load(Supplier<CompletableFuture<T>> request, Consumer<T> publish, Consumer<Throwable> failed) {
        long ticket;
        synchronized (this) { ticket = ++generation; }
        attempt(ticket, 1, request, publish, failed);
    }
    public synchronized void cancel() { generation++; }

    private void attempt(long ticket, int number, Supplier<CompletableFuture<T>> request, Consumer<T> publish, Consumer<Throwable> failed) {
        synchronized (this) { if (ticket != generation) return; }
        CompletableFuture<T> future;
        try { future = request.get(); }
        catch (RuntimeException error) { future = CompletableFuture.failedFuture(error); }
        future.whenComplete((value, error) -> publishExecutor.execute(() -> {
            synchronized (this) {
                if (ticket != generation) return;
                if (error == null) publish.accept(value);
                else if (number >= maxAttempts) failed.accept(error);
                else retryLater.accept(() -> attempt(ticket, number + 1, request, publish, failed));
            }
        }));
    }
}
