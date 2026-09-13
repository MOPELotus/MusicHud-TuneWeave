package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Rejects old image callbacks, while always releasing per-request (but never cache-owned) results. */
public final class ImagePublication<T> {
    private final Executor ui;
    private final Consumer<T> release;
    private volatile long generation;
    public ImagePublication(Executor ui, Consumer<T> release) { this.ui = ui; this.release = release; }
    public long next() { return ++generation; }
    public long current() { return generation; }
    public boolean isCurrent(long ticket) { return ticket == generation; }
    public void complete(long ticket, T value, Throwable error, boolean owned, Consumer<T> show, Consumer<Throwable> failure) {
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> { if (owned && value != null && released.compareAndSet(false, true)) release.accept(value); };
        try {
            ui.execute(() -> {
                try {
                    if (!isCurrent(ticket)) return;
                    if (error != null) failure.accept(error);
                    else {
                        try { show.accept(value); }
                        catch (RuntimeException problem) { if (isCurrent(ticket)) failure.accept(problem); }
                    }
                } finally { cleanup.run(); }
            });
        } catch (RuntimeException rejected) { cleanup.run(); }
    }
}
