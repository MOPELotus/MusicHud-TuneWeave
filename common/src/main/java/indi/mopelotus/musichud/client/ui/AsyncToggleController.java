package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.*;
import java.util.function.*;

/** Serial optimistic toggle writes, with binding-scoped completion and failure rollback. */
public final class AsyncToggleController {
    private final Executor ui;
    private long generation;
    private boolean initialized, confirmed, desired, writing;
    private Boolean pendingExternal;
    private Supplier<CompletableFuture<Boolean>> read;
    private Function<Boolean, CompletableFuture<?>> write;
    private BiConsumer<Boolean, Boolean> render = (value, enabled) -> {};
    private Consumer<Throwable> error = ignored -> {};

    public AsyncToggleController(Executor ui) { this.ui = ui; }
    public synchronized long binding() { return generation; }
    public synchronized boolean initialized() { return initialized; }

    public synchronized void bind(Supplier<CompletableFuture<Boolean>> read, Function<Boolean, CompletableFuture<?>> write,
                                  BiConsumer<Boolean, Boolean> render, Consumer<Throwable> error) {
        long ticket = ++generation; initialized = false; writing = false; pendingExternal = null;
        confirmed = false; desired = false;
        this.read = read; this.write = write; this.render = render; this.error = error;
        render.accept(false, false);
        if (generation == ticket) read(ticket);
    }

    private void read(long ticket) {
        CompletableFuture<Boolean> future;
        try { future = read.get(); } catch (RuntimeException failed) { future = CompletableFuture.failedFuture(failed); }
        future.whenComplete((value, failure) -> ui.execute(() -> {
            synchronized (this) {
                if (generation != ticket) return;
                if (failure != null) { render.accept(confirmed, true); if (generation == ticket) error.accept(failure); return; }
                initialized = true;
                confirmed = pendingExternal != null ? pendingExternal : Boolean.TRUE.equals(value);
                pendingExternal = null; desired = confirmed;
                render.accept(confirmed, true);
            }
        }));
    }

    public synchronized void request(boolean target) {
        if (write == null) return;
        if (!initialized) {
            long ticket = ++generation;
            render.accept(confirmed, false);
            if (ticket == generation && read != null) read(ticket);
            return;
        }
        desired = target;
        if (!writing) pump();
    }

    private void pump() {
        if (desired == confirmed) { render.accept(confirmed, true); return; }
        long ticket = generation; boolean attempted = desired;
        writing = true; render.accept(desired, false);
        if (ticket != generation) return;
        CompletableFuture<?> future;
        try { future = write.apply(attempted); } catch (RuntimeException failed) { future = CompletableFuture.failedFuture(failed); }
        future.whenComplete((ignored, failure) -> ui.execute(() -> {
            synchronized (this) {
                if (ticket != generation) return;
                writing = false;
                if (failure == null) confirmed = attempted;
                else {
                    if (desired == attempted) desired = confirmed;
                    error.accept(failure);
                }
                if (ticket == generation) pump();
            }
        }));
    }

    public void external(long ticket, boolean value) {
        ui.execute(() -> {
            synchronized (this) {
                if (ticket != generation) return;
                if (!initialized) { pendingExternal = value; return; }
                if (writing) return;
                confirmed = desired = value; render.accept(value, true);
            }
        });
    }

    public synchronized void unbind() {
        generation++; initialized = false; writing = false; read = null; write = null;
        render = (value, enabled) -> {}; error = ignored -> {};
    }
}
