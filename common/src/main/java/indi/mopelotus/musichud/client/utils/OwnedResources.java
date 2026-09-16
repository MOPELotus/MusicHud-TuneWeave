package indi.mopelotus.musichud.client.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns resources until explicit release or shutdown, including releases still queued on their owner thread. */
public final class OwnedResources implements AutoCloseable {
    private final Set<AutoCloseable> resources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CompletableFuture<?>> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<Exception> onCloseFailure;
    private volatile boolean stopped;

    public OwnedResources(Consumer<Exception> onCloseFailure) {
        this.onCloseFailure = onCloseFailure;
    }

    public boolean isStopped() {
        return stopped;
    }

    public synchronized <T extends AutoCloseable> T create(Supplier<T> factory) {
        return access(() -> {
            T resource = factory.get();
            if (resource != null) resources.add(resource);
            return resource;
        });
    }

    /** Protects borrowed CPU pixels from concurrent texture disposal. Never wait for another thread inside this call. */
    public synchronized <T> T access(Supplier<T> action) {
        if (stopped) throw new CancellationException("Graphics resources are shutting down");
        return action.get();
    }

    /** The gate is checked when the queued task actually executes, and shutdown unblocks its waiting caller. */
    public <T> CompletableFuture<T> submit(Executor executor, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            if (stopped) return CompletableFuture.failedFuture(new CancellationException("Graphics resources are shutting down"));
            pending.add(result);
        }
        try {
            executor.execute(() -> {
                try {
                    T value = access(() -> {
                        T produced = action.get();
                        // Once the action has returned, its caller owns the result. Do not cancel this
                        // handoff during shutdown (for example, a copied native player-skin bitmap).
                        pending.remove(result);
                        return produced;
                    });
                    result.complete(value);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                } finally {
                    synchronized (this) {
                        pending.remove(result);
                    }
                }
            });
        } catch (RuntimeException error) {
            synchronized (this) {
                pending.remove(result);
            }
            result.completeExceptionally(error);
        }
        return result;
    }

    /** Must run on the resource's owner thread. Late or repeated release is harmless. */
    public synchronized void release(AutoCloseable resource) {
        if (resources.remove(resource)) closeResource(resource);
    }

    /** Stop allocations now, but retain existing resources until their rendering thread has finished. */
    public void stop() {
        ArrayList<CompletableFuture<?>> waiting;
        synchronized (this) {
            stopped = true;
            waiting = new ArrayList<>(pending);
            pending.clear();
        }
        waiting.forEach(future -> future.completeExceptionally(new CancellationException("Graphics resources are shutting down")));
    }

    @Override
    public void close() {
        stop();
        synchronized (this) {
            for (AutoCloseable resource : resources) closeResource(resource);
            resources.clear();
        }
    }

    private void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception error) {
            onCloseFailure.accept(error);
        }
    }
}
