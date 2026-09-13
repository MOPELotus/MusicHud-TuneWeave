package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A single in-flight collection edit, retained until its UI commit has completed. */
public final class AsyncCollectionMutation {
    private final AtomicBoolean busy = new AtomicBoolean();
    public boolean isBusy() { return busy.get(); }

    public <T> boolean submit(Executor worker, Executor ui, BooleanSupplier current,
                             Supplier<T> preparedRequest, Consumer<T> commit,
                             Consumer<RuntimeException> failure, Runnable settled) {
        if (!busy.compareAndSet(false, true)) return false;
        try {
            worker.execute(() -> {
                T result = null;
                RuntimeException error = null;
                boolean attempted = false;
                try { if (current.getAsBoolean()) { attempted = true; result = preparedRequest.get(); } }
                catch (RuntimeException caught) { error = caught; }
                T value = result;
                RuntimeException problem = error;
                boolean ran = attempted;
                try {
                    ui.execute(() -> {
                        try {
                            if (ran && current.getAsBoolean()) {
                                if (problem == null) commit.accept(value);
                                else failure.accept(problem);
                            }
                        } catch (java.util.concurrent.CancellationException ignored) {
                            // Scope may expire between the validity check and atomic publication.
                        } finally { busy.set(false); settled.run(); }
                    });
                } catch (RuntimeException rejected) { busy.set(false); }
            });
        } catch (RuntimeException rejected) {
            busy.set(false); throw rejected;
        }
        return true;
    }
}
