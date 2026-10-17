package indi.mopelotus.musichud.client.utils.image;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Bounded source-byte reuse across artwork consumers, without sharing their processing results. */
final class SharedImageBytes {
    private final Cache<String, byte[]> cache;
    private final ImageRequests<String, byte[]> requests = new ImageRequests<>();
    private Object epoch = new Object();

    SharedImageBytes(long maxBytes) {
        cache = CacheBuilder.newBuilder().maximumWeight(maxBytes)
                .weigher((String key, byte[] value) -> value.length)
                .expireAfterAccess(Duration.ofMinutes(2)).build();
    }

    CompletableFuture<byte[]> get(String url, Supplier<CompletableFuture<byte[]>> download) {
        final Object generation;
        synchronized (this) {
            byte[] bytes = cache.getIfPresent(url);
            if (bytes != null) return CompletableFuture.completedFuture(bytes);
            generation = epoch;
        }
        return requests.get(url, () -> {
            synchronized (this) {
                byte[] bytes = cache.getIfPresent(url);
                if (bytes != null) return CompletableFuture.completedFuture(bytes);
            }
            return download.get().thenApply(bytes -> {
                synchronized (this) {
                    if (generation == epoch) cache.put(url, bytes);
                }
                return bytes;
            });
        });
    }

    synchronized void clear() {
        epoch = new Object();
        cache.invalidateAll();
        requests.clear();
    }
}
