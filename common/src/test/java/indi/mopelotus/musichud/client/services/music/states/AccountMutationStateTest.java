package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountMutationStateTest {
    @AfterEach void clear() { MusicEntityCache.clear(); }

    @Test void subscribeWritesOffCallerThreadAndNotifiesOnlyAfterSuccess() {
        var playlist = playlist(1); SequencedSet<Playlist> values = new LinkedHashSet<>();
        var work = new ArrayDeque<Runnable>(); var writes = new AtomicInteger(); var events = new ArrayList<Boolean>();
        var state = new SubscribeState<>(1, Playlist.class, id -> CompletableFuture.completedFuture(playlist),
                () -> CompletableFuture.completedFuture(values), () -> (entity, selected) -> writes.incrementAndGet(), work::add);
        var listener = state.onOthersModify(events::add);
        try {
            var result = state.subscribe(); assertEquals(0, writes.get()); assertTrue(values.isEmpty()); assertFalse(result.isDone());
            work.removeFirst().run(); result.join();
            assertEquals(1, writes.get()); assertEquals(List.of(true), events); assertTrue(values.contains(playlist));
        } finally { listener.unregister(); }
    }

    @Test void invalidatedSubscribeNeverWritesAndFailedUnsubscribeDoesNotChangeData() {
        var playlist = playlist(1); SequencedSet<Playlist> values = new LinkedHashSet<>();
        var work = new ArrayDeque<Runnable>(); var writes = new AtomicInteger();
        var state = new SubscribeState<>(1, Playlist.class, id -> CompletableFuture.completedFuture(playlist),
                () -> CompletableFuture.completedFuture(values), () -> (entity, selected) -> writes.incrementAndGet(), work::add);
        var pending = state.subscribe(); MusicEntityCache.clear(); work.removeFirst().run();
        assertThrows(CompletionException.class, pending::join); assertEquals(0, writes.get()); assertTrue(values.isEmpty());
        values.add(playlist);
        var failing = new SubscribeState<>(1, Playlist.class, id -> CompletableFuture.completedFuture(playlist),
                () -> CompletableFuture.completedFuture(values), () -> (entity, selected) -> { throw new IllegalStateException("offline"); }, work::add);
        var failure = failing.unsubscribe(); work.removeFirst().run(); assertThrows(CompletionException.class, failure::join);
        assertTrue(values.contains(playlist));
    }

    @Test void likedPlaylistRebindsToNewScopeAndLateDetachReleasesSubscription() {
        var active = new AtomicReference<>(playlist(1)); var firstLoad = new CompletableFuture<Playlist>();
        var work = new ArrayDeque<Runnable>(); var notices = new AtomicInteger();
        var state = new MusicTrackState(track(), () -> firstLoad, id -> CompletableFuture.completedFuture(active.get()),
                selected -> playlist -> null, work::add).currentUsersLikeList();
        var detached = state.onOthersModify(value -> notices.incrementAndGet()); detached.unregister();
        firstLoad.complete(active.get());
        var add = state.add(); work.removeFirst().run(); add.join();
        assertEquals(0, notices.get()); assertEquals(1, active.get().getTracks().size());
        MusicEntityCache.clear();
        var loads = new AtomicInteger();
        var reusable = new MusicTrackState(track(), () -> { loads.incrementAndGet(); return CompletableFuture.completedFuture(active.get()); },
                id -> CompletableFuture.completedFuture(active.get()), selected -> playlist -> null, work::add).currentUsersLikeList();
        reusable.isContained().join(); active.set(playlist(2)); MusicEntityCache.clear(); reusable.isContained().join();
        assertEquals(2, loads.get()); assertEquals(2, reusable.playlistId());
    }

    @Test void accountChangeDuringWriteCannotPublishOldLikeState() {
        var playlist = playlist(1); var work = new ArrayDeque<Runnable>();
        var state = new MusicTrackState(track(), () -> CompletableFuture.completedFuture(playlist),
                id -> CompletableFuture.completedFuture(playlist), selected -> value -> { MusicEntityCache.clear(); return null; }, work::add).currentUsersLikeList();
        var pending = state.add(); work.removeFirst().run();
        assertThrows(CompletionException.class, pending::join); assertTrue(playlist.getTracks().isEmpty());
    }

    private static Playlist playlist(long id) { return Playlist.fromTuneWeave(id, "netease:playlist:" + id, "List", "", 0, 0, Profile.ANONYMOUS); }
    private static MusicDetail track() { return MusicDetail.fromTuneWeave(10, "netease:10", "track", "Song", 1000, Album.NONE, List.of()); }
}
