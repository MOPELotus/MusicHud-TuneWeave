package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.QueueItem;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/** Queue snapshots are ordered when received, before parallel workers can reorder their publication. */
public final class QueueSnapshotPublication {
    private final Object lock;
    private final Consumer<Queue<QueueItem>> publish;
    private long revision;

    public QueueSnapshotPublication(Object lock, Consumer<Queue<QueueItem>> publish) {
        this.lock = lock;
        this.publish = publish;
    }

    public Runnable prepare(Queue<QueueItem> queue) {
        synchronized (lock) {
            var snapshot = List.copyOf(queue);
            long ticket = ++revision;
            return () -> {
                synchronized (lock) {
                    if (ticket == revision) publish.accept(new ArrayDeque<>(snapshot));
                }
            };
        }
    }

    public long revision() {
        synchronized (lock) { return revision; }
    }

    /** A full initial response must not overwrite any queue push received since the request began. */
    public boolean publishInitialIfUnchanged(long expected, Queue<QueueItem> queue) {
        synchronized (lock) {
            if (revision != expected) return false;
            var snapshot = new ArrayDeque<>(queue);
            revision++;
            publish.accept(snapshot);
            return true;
        }
    }

    public void invalidate() {
        synchronized (lock) { revision++; }
    }
}
