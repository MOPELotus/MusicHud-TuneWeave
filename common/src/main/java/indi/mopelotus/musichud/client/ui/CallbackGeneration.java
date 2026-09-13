package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.Executor;

/** Invalidates callbacks already queued on the UI executor when a view refreshes or detaches. */
public final class CallbackGeneration {
    private volatile long generation;
    public long next() { return ++generation; }
    public boolean isCurrent(long expected) { return generation == expected; }
    public void post(Executor executor, long expected, Runnable callback) {
        executor.execute(() -> {
            if (isCurrent(expected)) callback.run();
        });
    }
}
