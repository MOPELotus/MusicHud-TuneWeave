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
    record Snapshot<P, A, R>(P playlists, A albums, R artists) {}
    private record Read<T>(T value, Throwable failure) {}

    static <P, A, R> CompletableFuture<Snapshot<P, A, R>> merge(
            CompletableFuture<P> playlists, CompletableFuture<A> albums, CompletableFuture<R> artists,
            P emptyPlaylists, A emptyAlbums, R emptyArtists) {
        var p = observe(playlists);
        var a = observe(albums);
        var r = observe(artists);
        return CompletableFuture.allOf(p, a, r).thenApply(ignored -> {
            var pv = p.join(); var av = a.join(); var rv = r.join();
            if (pv.failure() != null && av.failure() != null && rv.failure() != null)
                throw new java.util.concurrent.CompletionException(pv.failure());
            return new Snapshot<>(pv.failure() == null ? pv.value() : emptyPlaylists,
                    av.failure() == null ? av.value() : emptyAlbums,
                    rv.failure() == null ? rv.value() : emptyArtists);
        });
    }

    private static <T> CompletableFuture<Read<T>> observe(CompletableFuture<T> future) {
        return future.handle((value, failure) -> {
            while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null)
                failure = failure.getCause();
            if (failure instanceof java.util.concurrent.CancellationException || failure instanceof Error)
                throw new java.util.concurrent.CompletionException(failure);
            if (failure == null && value == null) failure = new IllegalStateException("Missing account collection");
            if (failure != null) indi.mopelotus.musichud.MusicHud.getLogger(AccountCollectionModules.class)
                    .warn("An account collection section could not be loaded; retaining other sections");
            return new Read<>(value, failure);
        });
    }

    void invalidate() {
        playlists.invalidate();
        albums.invalidate();
        artists.invalidate();
    }
}
