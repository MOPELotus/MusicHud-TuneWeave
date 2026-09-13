package indi.mopelotus.musichud.client.services.music;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Independent request identities, failures and cache entries for each account section. */
final class AccountCollectionModules<P, A, R> {
    private final UserCollectionCache<P> playlists = new UserCollectionCache<>();
    private final UserCollectionCache<A> albums = new UserCollectionCache<>();
    private final UserCollectionCache<R> artists = new UserCollectionCache<>();

    CompletableFuture<P> playlists(boolean refresh, java.util.function.Function<java.util.function.Consumer<P>, Supplier<P>> prepare,
                                  java.util.function.Consumer<P> progress, Executor executor) {
        return playlists.loadProgress(refresh, prepare, progress, executor);
    }
    CompletableFuture<A> albums(boolean refresh, java.util.function.Function<java.util.function.Consumer<A>, Supplier<A>> prepare,
                               java.util.function.Consumer<A> progress, Executor executor) {
        return albums.loadProgress(refresh, prepare, progress, executor);
    }
    CompletableFuture<R> artists(boolean refresh, java.util.function.Function<java.util.function.Consumer<R>, Supplier<R>> prepare,
                                java.util.function.Consumer<R> progress, Executor executor) {
        return artists.loadProgress(refresh, prepare, progress, executor);
    }

    CompletableFuture<P> playlists(boolean refresh, Supplier<P> load, Executor executor) {
        return playlists.load(refresh, load, executor);
    }
    CompletableFuture<A> albums(boolean refresh, Supplier<A> load, Executor executor) {
        return albums.load(refresh, load, executor);
    }
    CompletableFuture<R> artists(boolean refresh, Supplier<R> load, Executor executor) {
        return artists.load(refresh, load, executor);
    }
    void invalidate() {
        playlists.invalidate();
        albums.invalidate();
        artists.invalidate();
    }
}
