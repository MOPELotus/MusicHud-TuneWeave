package indi.mopelotus.musichud.client.audio;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** A reload invalidates even reused context pointers/source IDs before vanilla destroys them. */
public final class SoundEngineEpoch {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static volatile long generation;
    private static volatile boolean ready = true;
    private SoundEngineEpoch() {}
    public static void beginReload() { invalidate(); }
    public static void invalidate() {
        LOCK.writeLock().lock();
        try { ready = false; generation++; }
        finally { LOCK.writeLock().unlock(); }
    }
    public static void ready() {
        LOCK.writeLock().lock();
        try { ready = true; }
        finally { LOCK.writeLock().unlock(); }
    }
    static long generation() { return generation; }
    static boolean available() { return ready; }
    static <T> T guarded(Supplier<T> operation) {
        LOCK.readLock().lock();
        try { return operation.get(); }
        finally { LOCK.readLock().unlock(); }
    }
}
