package indi.mopelotus.musichud.client.ui.components;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistMembershipLoaderTest {
    @Test void failedPlaylistDoesNotHideOthersAndNeverBecomesFalseMembership() {
        var result = PlaylistMembershipLoader.load(List.of(1,2,3,4), id -> switch (id) {
            case 1 -> CompletableFuture.completedFuture(true);
            case 2 -> CompletableFuture.failedFuture(new IllegalStateException("unavailable"));
            case 3 -> throw new IllegalArgumentException("malformed playlist");
            default -> CompletableFuture.completedFuture(false);
        }).join();
        assertEquals(Map.of(1,true,4,false),result);
    }
    @Test void cancellationAndTotalFailureRemainVisible() {
        assertThrows(CompletionException.class, () -> PlaylistMembershipLoader.load(List.of(1),
                id -> CompletableFuture.completedFuture(null)).join());
        var cancelled = PlaylistMembershipLoader.load(List.of(1,2), id -> id == 1
                ? CompletableFuture.failedFuture(new CancellationException("account changed"))
                : CompletableFuture.completedFuture(true));
        var failure = assertThrows(CompletionException.class,cancelled::join);
        assertInstanceOf(CancellationException.class,failure.getCause());
    }
    @Test void partialResultsAreNotPublishedUntilEveryIndependentCheckCompletes() {
        var late = new CompletableFuture<Boolean>();
        var result = PlaylistMembershipLoader.load(List.of(1,2), id -> id == 1 ? late : CompletableFuture.completedFuture(true));
        assertFalse(result.isDone()); late.completeExceptionally(new IllegalStateException("unavailable"));
        assertEquals(Map.of(2,true),result.join());
        assertEquals(Map.of(),PlaylistMembershipLoader.load(List.of(),id -> { throw new AssertionError(); }).join());
    }
}
