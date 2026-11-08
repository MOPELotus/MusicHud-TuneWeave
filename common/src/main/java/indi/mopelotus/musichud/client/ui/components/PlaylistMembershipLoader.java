package indi.mopelotus.musichud.client.ui.components;

import indi.mopelotus.musichud.MusicHud;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Membership checks are independent; an unknown result must never be treated as absent. */
final class PlaylistMembershipLoader {
    private PlaylistMembershipLoader() {}

    static <K> CompletableFuture<Map<K, Boolean>> load(Iterable<K> keys,
            Function<K, CompletableFuture<Boolean>> lookup) {
        var results = new ConcurrentHashMap<K, Boolean>();
        var firstFailure = new AtomicReference<Throwable>();
        var pending = new ArrayList<CompletableFuture<?>>();
        for (K key : keys) {
            CompletableFuture<Boolean> request;
            try { request = Objects.requireNonNull(lookup.apply(key)); }
            catch (RuntimeException failure) { request = CompletableFuture.failedFuture(failure); }
            pending.add(request.handle((value, failure) -> {
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                if (failure instanceof CancellationException || failure instanceof Error) throw new CompletionException(failure);
                if (failure == null && value != null) results.put(key, value);
                else {
                    firstFailure.compareAndSet(null, failure == null ? new IllegalStateException("Missing playlist membership") : failure);
                    MusicHud.getLogger(PlaylistMembershipLoader.class).warn("Skipped an unavailable playlist membership check");
                }
                return null;
            }));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            if (results.isEmpty() && firstFailure.get() != null) throw new CompletionException(firstFailure.get());
            return Map.copyOf(results);
        });
    }
}
