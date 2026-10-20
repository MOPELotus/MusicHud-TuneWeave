package indi.mopelotus.musichud.client.services;

import java.util.function.BooleanSupplier;

/** Hands API-worker status changes to the client tick without capturing client state. */
final class ApiLoginRestoreGate {
    private Object source;
    private long generation;
    private boolean running;
    private boolean pending;

    synchronized void bind(Object source, boolean running) {
        this.source = source;
        this.running = running;
        pending = source != null && running;
        generation++;
    }

    synchronized void status(Object source, boolean running) {
        if (this.source != source || this.running == running) return;
        this.running = running;
        pending = running;
        generation++;
    }

    void tick(BooleanSupplier ready, Runnable restore) {
        long expected;
        synchronized (this) {
            if (!pending || !running) return;
            expected = generation;
        }
        if (!ready.getAsBoolean()) return;
        synchronized (this) {
            if (!pending || !running || generation != expected) return;
            pending = false;
        }
        restore.run();
    }
}
