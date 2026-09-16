package indi.mopelotus.musichud.client.utils;

import java.util.concurrent.TimeUnit;

/** Lets a UI thread finish even when it is waiting for a frame the render thread will no longer consume. */
public final class ShutdownWaiter {
    private ShutdownWaiter() {}

    public static boolean await(Thread thread, Object frameLock, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                if (frameLock != null) {
                    synchronized (frameLock) {
                        frameLock.notifyAll();
                    }
                }
                // Keep notifying: a final queued traversal can enter another frame wait after the first wakeup.
                thread.join(Math.max(1, Math.min(10, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
