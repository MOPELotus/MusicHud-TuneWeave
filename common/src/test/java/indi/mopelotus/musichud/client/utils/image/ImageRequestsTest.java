package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageRequestsTest {
    @Test void sharesPendingWorkAndRetriesAfterSynchronousFailures() {
        var cache = new ImageRequests<String, String>(); var network = new CompletableFuture<String>();
        var first = cache.get("cover", () -> network);
        assertSame(first, cache.get("cover", () -> { fail(); return null; }));
        network.complete("image"); assertEquals("image", first.join());
        assertThrows(CompletionException.class, () -> cache.get("cover", () -> { throw new IllegalStateException(); }).join());
        assertEquals("retry", cache.get("cover", () -> CompletableFuture.completedFuture("retry")).join());
    }
    @Test void completionMayReenterSameKeyWithoutRemovingNewRequest() {
        var cache = new ImageRequests<String, String>(); var firstNetwork = new CompletableFuture<String>();
        var nextNetwork = new CompletableFuture<String>();
        var first = cache.get("same", () -> firstNetwork);
        var next = first.thenCompose(value -> cache.get("same", () -> nextNetwork));
        firstNetwork.complete("old");
        assertFalse(next.isDone());
        assertSame(cache.get("same", () -> { fail(); return null; }), cache.get("same", () -> { fail(); return null; }));
        nextNetwork.complete("new"); assertEquals("new", next.join());
    }
}
