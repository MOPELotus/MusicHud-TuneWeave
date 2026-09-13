package indi.mopelotus.musichud.client.services;

import java.util.function.Supplier;

/** Debounced actions share the connection owner's lock, avoiding check/act and lock-order races. */
public final class ConnectionActionGate {
    private final Object lock;
    private Object epoch = new Object();
    public ConnectionActionGate(Object lock) { this.lock = lock; }
    public void invalidate() { synchronized (lock) { epoch = new Object(); } }
    public Runnable prepare(Supplier<Object> connection, Runnable action) {
        synchronized (lock) {
            Object ticket = epoch = new Object();
            Object expected = connection.get();
            return () -> {
                synchronized (lock) {
                    if (expected == null || epoch != ticket || connection.get() != expected) return;
                    epoch = new Object();
                    action.run();
                }
            };
        }
    }
}
