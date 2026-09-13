package indi.mopelotus.musichud.network;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded channel-registration retry tied to a specific connection and plugin lifetime. */
public final class DeferredPluginSend implements Runnable {
    private final BooleanSupplier active, registered;
    private final Runnable send;
    private final Consumer<Runnable> later;
    private final int maxRetries;
    private int retries;
    private boolean finished;

    public DeferredPluginSend(BooleanSupplier active, BooleanSupplier registered, Runnable send,
                              Consumer<Runnable> later, int maxRetries) {
        if (maxRetries < 0 || maxRetries > 1000) throw new IllegalArgumentException("Invalid send retry limit");
        this.active = active; this.registered = registered; this.send = send; this.later = later; this.maxRetries = maxRetries;
    }

    @Override public synchronized void run() {
        if (finished) return;
        if (!active.getAsBoolean()) { finished = true; return; }
        if (registered.getAsBoolean()) { finished = true; send.run(); return; }
        if (retries++ >= maxRetries) { finished = true; return; }
        later.accept(this);
    }
}
