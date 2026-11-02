package indi.mopelotus.musichud.client.companion;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** A stopped/replaced generation may finish downloading, but may never start a child. */
final class ManagedCompanionProcess {
    interface Child { void stop(); boolean alive(); }
    interface Launcher { Child launch(AtomicBoolean cancelled) throws Exception; }
    private final Executor worker;
    private long generation;
    private boolean closed;
    private AtomicBoolean cancellation = new AtomicBoolean();
    private Child child;
    private volatile String failure = "";
    private volatile boolean busy;

    ManagedCompanionProcess(Executor worker) { this.worker = worker; }
    synchronized void start(Launcher launcher) {
        if (closed || busy || child != null && child.alive()) return;
        long expected = ++generation;
        AtomicBoolean token = cancellation = new AtomicBoolean();
        busy = true;
        failure = "";
        worker.execute(() -> {
            Child started = null;
            try {
                started = launcher.launch(token);
                synchronized (this) {
                    if (closed || generation != expected || token.get()) {
                        if (started != null) started.stop();
                    } else child = started;
                }
            } catch (Exception error) {
                synchronized (this) {
                    if (generation == expected && !token.get()) failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                }
            } finally {
                synchronized (this) { if (generation == expected) busy = false; }
            }
        });
    }
    synchronized void stop() {
        generation++;
        cancellation.set(true);
        busy = false;
        if (child != null) { child.stop(); child = null; }
    }
    synchronized void close() { closed = true; stop(); }
    boolean busy() { return busy; }
    String failure() { return failure; }
    synchronized boolean running() { return child != null && child.alive(); }
}
