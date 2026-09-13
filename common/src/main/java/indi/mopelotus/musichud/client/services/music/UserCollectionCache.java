package indi.mopelotus.musichud.client.services.music;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;

/** Publishes only the latest account collection request; failed loads are never cached. */
final class UserCollectionCache<T> {
    private Object request;
    private T value;
    private Object scope;
    private CompletableFuture<T> pending;
    private T partial;
    private final List<Consumer<T>> listeners = new ArrayList<>();
    private final java.util.function.LongSupplier clock;
    private long loadedAt;

    UserCollectionCache() { this(System::currentTimeMillis); }
    UserCollectionCache(java.util.function.LongSupplier clock) { this.clock = clock; }

    synchronized CompletableFuture<T> load(boolean refresh, Supplier<T> loader, Executor executor) {
        return loadProgress(refresh, ignored -> loader, ignored -> {}, executor);
    }

    synchronized CompletableFuture<T> loadProgress(boolean refresh, Function<Consumer<T>, Supplier<T>> prepare,
                                                    Consumer<T> progress, Executor executor) {
        Object generation = MusicEntityCache.captureGeneration();
        long now = clock.getAsLong();
        if (!refresh && scope == generation && value != null && now >= loadedAt && now - loadedAt < 300_000) {
            return CompletableFuture.completedFuture(value);
        }
        if (!refresh && scope == generation && pending != null && !pending.isDone()) {
            listeners.add(progress);
            if (partial != null) progress.accept(partial);
            return pending;
        }
        Object requested = new Object();
        scope = generation;
        request = requested;
        value = null;
        partial = null;
        listeners.clear();
        listeners.add(progress);
        Supplier<T> loader = prepare.apply(page -> {
            synchronized (this) {
                if (request != requested) throw new CancellationException("Account page was superseded");
                MusicEntityCache.publish(generation, () -> {
                    partial = page;
                    List.copyOf(listeners).forEach(listener -> listener.accept(page));
                });
            }
        });
        pending = CompletableFuture.supplyAsync(loader, executor).thenApply(result -> {
            synchronized (this) {
                if (request != requested) {
                    throw new CancellationException("Account collection request was superseded");
                }
                MusicEntityCache.publish(generation, () -> { value = result; loadedAt = clock.getAsLong(); });
                return result;
            }
        }).whenComplete((result, error) -> {
            synchronized (this) {
                if (request == requested) { listeners.clear(); partial = null; }
            }
        });
        return pending;
    }

    synchronized void invalidate() {
        request = null;
        value = null;
        pending = null;
        scope = null;
        partial = null;
        listeners.clear();
        MusicEntityCache.clear();
    }
}
