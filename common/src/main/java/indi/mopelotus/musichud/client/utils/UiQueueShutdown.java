package indi.mopelotus.musichud.client.utils;

import java.util.concurrent.atomic.AtomicBoolean;

/** Stops frame production before the queue starts draining its remaining messages. */
public final class UiQueueShutdown {
    private UiQueueShutdown() {}

    public static Runnable wrap(Runnable stopFrameCallbacks, Runnable quitQueue) {
        AtomicBoolean finished = new AtomicBoolean();
        return () -> {
            if (!finished.compareAndSet(false, true)) return;
            try {
                stopFrameCallbacks.run();
            } finally {
                quitQueue.run();
            }
        };
    }
}
