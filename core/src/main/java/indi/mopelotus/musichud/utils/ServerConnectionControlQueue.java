package indi.mopelotus.musichud.utils;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.Executor;

/** Connect/Disconnect are ordered before membership checks, independently for each physical peer. */
public final class ServerConnectionControlQueue {
    private static final int MAX_PENDING = 64;
    private final Executor executor;
    private final IdentityHashMap<Object, ArrayDeque<Runnable>> pending = new IdentityHashMap<>();

    public ServerConnectionControlQueue(Executor executor) { this.executor = executor; }

    public void execute(Object connection, Runnable action) {
        boolean start;
        ArrayDeque<Runnable> queue;
        synchronized (pending) {
            queue = pending.get(connection);
            start = queue == null;
            if (start) {
                queue = new ArrayDeque<>();
                pending.put(connection, queue);
            }
            // Floods cannot retain unbounded control work. The newest membership intent wins.
            if (queue.size() == MAX_PENDING) queue.clear();
            queue.addLast(action);
        }
        if (start) {
            try { executor.execute(() -> drain(connection)); }
            catch (RuntimeException e) {
                synchronized (pending) { pending.remove(connection); }
                throw e;
            }
        }
    }

    private void drain(Object connection) {
        while (true) {
            Runnable action;
            synchronized (pending) {
                var queue = pending.get(connection);
                action = queue.pollFirst();
                if (action == null) {
                    pending.remove(connection);
                    return;
                }
            }
            // Production actions handle their own errors, keeping the next control operation runnable.
            action.run();
        }
    }
}
