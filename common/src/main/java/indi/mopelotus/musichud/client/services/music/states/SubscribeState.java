package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.IdentifiedBeans;
import indi.mopelotus.musichud.beans.state.ISubscribeState;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.interfaces.Unregister;
import java.util.SequencedSet;
import java.util.concurrent.*;
import java.util.function.*;

public class SubscribeState<T extends IdentifiedBeans> implements ISubscribeState<T> {
    private record Key(Object scope, long id, Class<?> type) {}
    private record Cached<T>(Object scope, T value) {}
    private static final ConcurrentHashMap<Key, CopyOnWriteArrayList<Consumer<Boolean>>> LISTENERS = new ConcurrentHashMap<>();
    private final long id;
    private final Class<T> type;
    private final Function<Long, CompletableFuture<T>> fullLoader;
    private final Supplier<CompletableFuture<SequencedSet<T>>> subscribedSet;
    private final Supplier<BiConsumer<T, Boolean>> prepareWrite;
    private final Executor executor;
    private volatile Cached<T> cached;

    public SubscribeState(long id, Class<T> type, Function<Long, CompletableFuture<T>> fullLoader,
                          Supplier<CompletableFuture<SequencedSet<T>>> subscribedSet, Supplier<BiConsumer<T, Boolean>> prepareWrite) {
        this(id, type, fullLoader, subscribedSet, prepareWrite, MusicHud.EXECUTOR);
    }

    SubscribeState(long id, Class<T> type, Function<Long, CompletableFuture<T>> fullLoader,
                   Supplier<CompletableFuture<SequencedSet<T>>> subscribedSet, Supplier<BiConsumer<T, Boolean>> prepareWrite, Executor executor) {
        this.id = id; this.type = type; this.fullLoader = fullLoader; this.subscribedSet = subscribedSet;
        this.prepareWrite = prepareWrite; this.executor = executor;
    }

    @Override public long getBeanId() { return id; }

    private CompletableFuture<T> load(Object scope) {
        Cached<T> current = cached;
        if (current != null && current.scope() == scope) return CompletableFuture.completedFuture(current.value());
        return fullLoader.apply(id).thenApply(value -> {
            MusicEntityCache.publish(scope, () -> cached = new Cached<>(scope, value));
            return value;
        });
    }

    @Override public CompletableFuture<Boolean> isSubscribed() {
        Object scope = MusicEntityCache.captureGeneration();
        return subscribedSet.get().thenApply(values -> {
            MusicEntityCache.publish(scope, () -> {});
            return values.stream().anyMatch(value -> value.getId() == id);
        });
    }

    @Override public CompletableFuture<Void> subscribe() { return modify(true); }
    @Override public CompletableFuture<Void> unsubscribe() { return modify(false); }

    private CompletableFuture<Void> modify(boolean selected) {
        Object scope = MusicEntityCache.captureGeneration();
        final BiConsumer<T, Boolean> write;
        try { write = prepareWrite.get(); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        return CompletableFuture.runAsync(() -> {
            T entity = load(scope).join();
            SequencedSet<T> values = subscribedSet.get().join();
            MusicEntityCache.publish(scope, () -> {});
            write.accept(entity, selected);
            MusicEntityCache.publish(scope, () -> {
                if (selected) values.addFirst(entity);
                else values.removeIf(value -> value.getId() == id);
                var listeners = LISTENERS.get(new Key(scope, id, type));
                if (listeners != null) listeners.forEach(listener -> listener.accept(selected));
            });
        }, executor);
    }
    @Override public Unregister onOthersModify(Consumer<Boolean> listener) {
        Key key = new Key(MusicEntityCache.captureGeneration(), id, type);
        LISTENERS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> LISTENERS.computeIfPresent(key, (ignored, values) -> {
            values.remove(listener); return values.isEmpty() ? null : values;
        });
    }
}