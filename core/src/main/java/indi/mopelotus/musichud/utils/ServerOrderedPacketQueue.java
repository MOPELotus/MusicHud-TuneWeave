package indi.mopelotus.musichud.utils;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.Executor;

/** FIFO mutations per physical connection; unrelated connections retain independent workers. */
public final class ServerOrderedPacketQueue {
    private static final int MAX_PENDING = 4096;
    private final Executor executor;
    private final IdentityHashMap<Object, ArrayDeque<Runnable>> pending = new IdentityHashMap<>();

    public ServerOrderedPacketQueue(Executor executor) { this.executor = executor; }

    public boolean execute(Object connection, Runnable action) {
        synchronized (pending) {
            var existing = pending.get(connection);
            if (existing != null) {
                // Reject new overload work without discarding or reordering accepted mutations.
                if (existing.size() >= MAX_PENDING) return false;
                existing.addLast(action);
                return true;
            }
            var queue = new ArrayDeque<Runnable>();
            queue.addLast(action);
            pending.put(connection, queue);
            // Publish the worker atomically with its first request. A rejected submission must
            // not discard requests that another caller has already been told were accepted.
            try { executor.execute(() -> drain(connection)); }
            catch (RuntimeException error) {
                pending.remove(connection);
                throw error;
            }
            return true;
        }
    }

    private void drain(Object connection) {
        while (true) {
            Runnable action;
            synchronized (pending) {
                var queue = pending.get(connection);
                action = queue.pollFirst();
                if (action == null) { pending.remove(connection); return; }
            }
            // The receiver handles individual packet failures, so later accepted work still runs.
            action.run();
        }
    }
}
