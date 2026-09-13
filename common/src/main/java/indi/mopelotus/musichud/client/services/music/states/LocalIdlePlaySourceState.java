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

    @Override
    public synchronized void loadFromConfig() {
        if (!loaded) {
            loaded = true;
            long generation = ++loadGeneration;
            Set<IdlePlaySource> idlePlaySources = Set.copyOf(configuredSources.get());
            if (!idlePlaySources.isEmpty()) {
                executor.execute(() -> {
                    for (IdlePlaySource idlePlaySource : idlePlaySources) {
                        try {
                            var loading = referenceLoader != null && !idlePlaySource.getSourceReference().isBlank()
                                    ? referenceLoader.apply(idlePlaySource.getType(), idlePlaySource.getSourceReference())
                                    : load(idlePlaySource.getType(), idlePlaySource.getId());
                            loading.thenAcceptAsync(musicCollection -> {
                                synchronized (LocalIdlePlaySourceState.this) {
                                    // A failed sibling makes the batch retryable, but must not
                                    // discard successful sources still wanted by the user.
                                    if (generation != loadGeneration
                                            || configuredSources.get().stream().noneMatch(idlePlaySource::equals)) return;
                                    add(musicCollection);
                                }
                            }, executor).exceptionally(error -> {
                                synchronized (LocalIdlePlaySourceState.this) {
                                    if (generation == loadGeneration && loaded) {
                                        loaded = false;
                                    }
                                }
                                logger.warn("Failed to restore idle play source {}", idlePlaySource, error);
                                return null;
                            });
                        } catch (Exception e) {
                            synchronized (LocalIdlePlaySourceState.this) {
                                if (generation == loadGeneration && loaded) {
                                    loaded = false;
                                }
                            }
                            logger.error("Failed to load idle play source playlist with idlePlaySource:{}", idlePlaySource, e);
                        }
                    }
                });
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
            IdlePlaySource key = new IdlePlaySource(idlePlaySourceCollection.getId(), idlePlaySourceCollection.getClass());
            Object request = new Object();
            pendingAdds.put(key, request);
            load(key.getType(), key.getId()).whenComplete((complete, error) -> {
                synchronized (LocalIdlePlaySourceState.this) {
                    if (pendingAdds.get(key) != request) return;
                    pendingAdds.remove(key);
                    if (error != null) { logger.warn("Failed to load idle source", error); return; }
                    install(complete);
                }
            });
            return;
        }
        install(idlePlaySourceCollection);
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
    }

    @Override
    public synchronized void remove(MusicCollection collection) {
        pendingAdds.remove(new IdlePlaySource(collection.getId(), collection.getClass()));
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
        loadGeneration++;
        loaded = false;
    }
}
