package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.user.ProfileConfigData;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.AddToIdlePlaySourceMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.RemoveFromIdlePlaySourceMessage;
import lombok.Getter;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import indi.mopelotus.musichud.network.payloads.C2SPayload;

public class LocalIdlePlaySourceState extends AbstractIdlePlaySourceLayerState {
    private static final Logger logger = MusicHud.getLogger(LocalIdlePlaySourceState.class);
    private final Supplier<Set<IdlePlaySource>> configuredSources;
    private final Runnable saveConfig;
    private final BiFunction<Class<?>, Long, CompletableFuture<? extends MusicCollection>> loader;
    private final Consumer<C2SPayload> send;
    private final Executor executor;
    private BiFunction<Class<?>, String, CompletableFuture<? extends MusicCollection>> referenceLoader;

    public LocalIdlePlaySourceState() {
        this(() -> ProfileConfigData.getInstance().getIdlePlaySources(),
                () -> ProfileConfigData.getInstance().saveToConfig(),
                (type, id) -> type == Album.class
                        ? MusicService.getInstance().loadAlbumDetail(id, false)
                        : MusicService.getInstance().loadPlaylistDetail(id, false),
                payload -> IClientNetworkService.getInstance().sendToServer(payload), MusicHud.EXECUTOR);
        referenceLoader = (type, reference) -> {
            var tuneWeave = indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService.getInstance();
            return CompletableFuture.supplyAsync(tuneWeave.prepareRequest(() -> type == Album.class
                    ? tuneWeave.loadAlbumDetail(reference) : tuneWeave.loadPlaylistDetail(reference)), executor);
        };
    }

    LocalIdlePlaySourceState(Supplier<Set<IdlePlaySource>> configuredSources, Runnable saveConfig,
                            BiFunction<Class<?>, Long, CompletableFuture<? extends MusicCollection>> loader,
                            Consumer<C2SPayload> send, Executor executor,
                            BiFunction<Class<?>, String, CompletableFuture<? extends MusicCollection>> referenceLoader) {
        this(configuredSources, saveConfig, loader, send, executor);
        this.referenceLoader = referenceLoader;
    }

    LocalIdlePlaySourceState(Supplier<Set<IdlePlaySource>> configuredSources, Runnable saveConfig,
                            BiFunction<Class<?>, Long, CompletableFuture<? extends MusicCollection>> loader,
                            Consumer<C2SPayload> send, Executor executor) {
        this.configuredSources = configuredSources;
        this.saveConfig = saveConfig;
        this.loader = loader;
        this.send = send;
        this.executor = executor;
    }
    @Getter
    private volatile boolean loaded = false;
    private long loadGeneration;
    private final java.util.Map<IdlePlaySource, Object> pendingAdds = new java.util.HashMap<>();

    private final java.util.Map<IdlePlaySource, IdlePlaySource> loadErrors = new java.util.HashMap<>();
    private final java.util.Map<IdlePlaySource, CompletableFuture<Boolean>> recoveries = new java.util.HashMap<>();
    private final Set<Consumer<IdlePlaySource>> errorListeners = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private IdlePlaySource sourceOf(MusicCollection collection) {
        return new IdlePlaySource(collection.getId(), collection.getClass(), getPlayMode(collection))
                .withReference(collection instanceof Playlist p ? p.getSourceRef() : ((Album) collection).getSourceRef());
    }

    private CompletableFuture<? extends MusicCollection> loadSource(IdlePlaySource source) {
        return referenceLoader != null && !source.getSourceReference().isBlank()
                ? referenceLoader.apply(source.getType(), source.getSourceReference())
                : load(source.getType(), source.getId());
    }

    private void markError(IdlePlaySource source) {
        loadErrors.put(source, source);
        errorListeners.forEach(listener -> listener.accept(source));
    }

    private void clearError(IdlePlaySource source) {
        if (loadErrors.remove(source) != null) errorListeners.forEach(listener -> listener.accept(source));
    }

    @Override public synchronized boolean isInLoadError(Class<?> type, long id) {
        return loadErrors.containsKey(new IdlePlaySource(id, type));
    }

    @Override public indi.mopelotus.musichud.interfaces.Unregister onLoadErrorChanged(Consumer<IdlePlaySource> listener) {
        errorListeners.add(listener);
        return () -> errorListeners.remove(listener);
    }

    @Override public synchronized CompletableFuture<Boolean> recover(Class<?> type, long id) {
        IdlePlaySource key = new IdlePlaySource(id, type);
        if (recoveries.containsKey(key)) return recoveries.get(key);
        IdlePlaySource source = loadErrors.get(key);
        if (source == null) return CompletableFuture.completedFuture(false);
        long generation = loadGeneration;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        recoveries.put(key, result);
        try {
            executor.execute(() -> {
                synchronized (LocalIdlePlaySourceState.this) {
                    if (generation != loadGeneration || recoveries.get(key) != result) return;
                }
                try {
                    loadSource(source).whenComplete((collection, error) -> {
                        synchronized (LocalIdlePlaySourceState.this) {
                            if (generation != loadGeneration || recoveries.get(key) != result) return;
                            recoveries.remove(key);
                            if (error == null) {
                                try { validate(source, collection); install(collection); result.complete(true); }
                                catch (Exception failure) { markError(source); result.complete(false); }
                            } else { markError(source); result.complete(false); }
                        }
                    });
                } catch (Exception failure) {
                    synchronized (LocalIdlePlaySourceState.this) {
                        if (recoveries.get(key) != result) return;
                        recoveries.remove(key);
                        result.complete(false);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            recoveries.remove(key);
            result.complete(false);
        }
        return result;
    }

    @Override
    public synchronized void loadFromConfig() {
        if (!loaded) {
            loaded = true;
            long generation = ++loadGeneration;
            Set<IdlePlaySource> idlePlaySources = Set.copyOf(configuredSources.get());
            if (!idlePlaySources.isEmpty()) {
                try { executor.execute(() -> {
                    for (IdlePlaySource idlePlaySource : idlePlaySources) {
                        try {
                            var loading = loadSource(idlePlaySource);
                            loading.thenAcceptAsync(musicCollection -> {
                                synchronized (LocalIdlePlaySourceState.this) {
                                    // A failed sibling makes the batch retryable, but must not
                                    // discard successful sources still wanted by the user.
                                    if (generation != loadGeneration
                                            || configuredSources.get().stream().noneMatch(idlePlaySource::equals)) return;
                                    validate(idlePlaySource, musicCollection);
                                    add(musicCollection);
                                }
                            }, executor).exceptionally(error -> {
                                synchronized (LocalIdlePlaySourceState.this) {
                                    if (generation == loadGeneration) {
                                        loaded = false;
                                        if (configuredSources.get().contains(idlePlaySource)) markError(idlePlaySource);
                                    }
                                }
                                logger.warn("Failed to restore idle play source {}", idlePlaySource, error);
                                return null;
                            });
                        } catch (Exception e) {
                            synchronized (LocalIdlePlaySourceState.this) {
                                if (generation == loadGeneration) {
                                    loaded = false;
                                    if (configuredSources.get().contains(idlePlaySource)) markError(idlePlaySource);
                                }
                            }
                            logger.error("Failed to load idle play source playlist with idlePlaySource:{}", idlePlaySource, e);
                        }
                    }
                }); } catch (java.util.concurrent.RejectedExecutionException error) {
                    loaded = false;
                    idlePlaySources.forEach(this::markError);
                }
            }
        }
    }

    @Override
    public CompletableFuture<? extends MusicCollection> load(Class<?> type, long id) {
        if (type == Album.class || type == Playlist.class) {
            return loader.apply(type, id);
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported idle source type"));
    }

    @Override
    public synchronized void add(MusicCollection idlePlaySourceCollection) {
        if (idlePlaySourceCollection.getMusicDetails().size() < idlePlaySourceCollection.getMusicTrackCount()) {
            IdlePlaySource key = sourceOf(idlePlaySourceCollection);
            Object request = new Object();
            pendingAdds.put(key, request);
            CompletableFuture<? extends MusicCollection> loading;
            try { loading = loadSource(key); }
            catch (Exception failure) { pendingAdds.remove(key); markError(key); return; }
            loading.whenComplete((complete, error) -> {
                synchronized (LocalIdlePlaySourceState.this) {
                    if (pendingAdds.get(key) != request) return;
                    pendingAdds.remove(key);
                    if (error != null) { markError(key); logger.warn("Failed to load idle source", error); return; }
                    try { validate(key, complete); install(complete); }
                    catch (Exception failure) { markError(key); logger.warn("Failed to publish idle source", failure); }
                }
            });
            return;
        }
        install(idlePlaySourceCollection);
    }

    private static void validate(IdlePlaySource source, MusicCollection collection) {
        if (collection == null || source.getId() != collection.getId() || source.getType() != collection.getClass()
                || collection.getMusicDetails().size() < collection.getMusicTrackCount()) {
            throw new IllegalStateException("Incomplete or mismatched idle source details");
        }
    }

    private void install(MusicCollection idlePlaySourceCollection) {
        MusicCollection collection = normalize(idlePlaySourceCollection);
        IdlePlaySource idlePlaySource = new IdlePlaySource(collection.getId(), collection.getClass(), getPlayMode(collection))
                .withReference(collection instanceof Playlist playlist ? playlist.getSourceRef() : ((Album) collection).getSourceRef());
        pendingAdds.remove(idlePlaySource);
        if (sources.stream().noneMatch(s -> s.equalsLoose(collection))) {
            sources.add(collection);
            notifyAdd(collection);
            notifyChange(collection);
            configuredSources.get().add(idlePlaySource);
            saveConfig.run();
        }
        send.accept(new AddToIdlePlaySourceMessage(idlePlaySource, collection));
        clearError(idlePlaySource);
    }

    @Override public synchronized indi.mopelotus.musichud.beans.api.IdlePlayMode getPlayMode(MusicCollection collection) {
        return configuredSources.get().stream().filter(source -> source.getId() == collection.getId()
                && source.getType() == collection.getClass()).map(IdlePlaySource::getMode).findFirst()
                .orElse(indi.mopelotus.musichud.beans.api.IdlePlayMode.RANDOM);
    }

    @Override public synchronized void setPlayMode(MusicCollection collection, indi.mopelotus.musichud.beans.api.IdlePlayMode mode) {
        var source = new IdlePlaySource(collection.getId(), collection.getClass(), mode)
                .withReference(collection instanceof Playlist playlist ? playlist.getSourceRef() : ((Album) collection).getSourceRef());
        configuredSources.get().remove(source);
        configuredSources.get().add(source);
        saveConfig.run();
        add(collection);
        notifyChange(collection);
    }

    @Override
    public synchronized void remove(MusicCollection collection) {
        IdlePlaySource key = sourceOf(collection);
        pendingAdds.remove(key);
        var recovery = recoveries.remove(key);
        if (recovery != null) recovery.complete(false);
        clearError(key);
        sources.removeIf(c -> c.getId() == collection.getId() && c.getClass() == collection.getClass());
        notifyRemove(collection);
        notifyChange(collection);
        IdlePlaySource idlePlaySource = new IdlePlaySource(collection.getId(), collection.getClass());
        configuredSources.get().removeIf(idlePlaySource::equals);
        saveConfig.run();
        send.accept(new RemoveFromIdlePlaySourceMessage(idlePlaySource));
    }

    @Override
    public synchronized void reset() {
        pendingAdds.clear();
        var canceled = java.util.List.copyOf(recoveries.values());
        recoveries.clear();
        canceled.forEach(future -> future.complete(false));
        var errors = java.util.List.copyOf(loadErrors.keySet());
        loadErrors.clear();
        errors.forEach(source -> errorListeners.forEach(listener -> listener.accept(source)));
        loadGeneration++;
        loaded = false;
    }
}
