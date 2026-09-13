package indi.mopelotus.musichud.client.utils.image;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Coalesces matching downloads, including synchronous completion and failure without map recursion. */
public final class ImageRequests<K, T> {
    private final ConcurrentHashMap<K, CompletableFuture<T>> pending = new ConcurrentHashMap<>();
    public void clear() { pending.clear(); }
    public int size() { return pending.size(); }
    public CompletableFuture<T> get(K key, Supplier<CompletableFuture<T>> request) {
        var result = new CompletableFuture<T>();
        var existing = pending.putIfAbsent(key, result);
        if (existing != null) return existing;
        try {
            request.get().whenComplete((value, error) -> {
                pending.remove(key, result);
                if (error == null) result.complete(value); else result.completeExceptionally(error);
            });
        } catch (RuntimeException error) {
            pending.remove(key, result); result.completeExceptionally(error);
        }
        return result;
    }
}
