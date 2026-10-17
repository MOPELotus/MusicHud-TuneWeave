package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SharedImageBytesTest {
    @Test void differentProcessorsShareDownloadButNotTheirResults() {
        var cache = new SharedImageBytes(8);
        var request = new CompletableFuture<byte[]>();
        AtomicInteger starts = new AtomicInteger();
        var one = cache.get("art", () -> { starts.incrementAndGet(); return request; }).thenApply(bytes -> bytes.length);
        var two = cache.get("art", () -> { starts.incrementAndGet(); return request; }).thenApply(bytes -> bytes[0]);
        request.complete(new byte[]{7, 8});
        assertEquals(2, one.join()); assertEquals((byte) 7, two.join()); assertEquals(1, starts.get());
        assertEquals(2, cache.get("art", () -> { fail("Download must be cached"); return null; }).join().length);
    }
    @Test void oldCompletionCannotRepopulateCacheAfterClear() {
        var cache = new SharedImageBytes(8);
        var old = new CompletableFuture<byte[]>();
        cache.get("art", () -> old); cache.clear(); old.complete(new byte[]{1});
        assertArrayEquals(new byte[]{2}, cache.get("art", () -> CompletableFuture.completedFuture(new byte[]{2})).join());
    }
    @Test void failuresCanRetryAndOversizedItemsAreNotRetained() {
        var cache = new SharedImageBytes(1);
        assertTrue(cache.get("art", () -> CompletableFuture.failedFuture(new IllegalStateException())).isCompletedExceptionally());
        assertEquals(2, cache.get("art", () -> CompletableFuture.completedFuture(new byte[2])).join().length);
        assertEquals(1, cache.get("art", () -> CompletableFuture.completedFuture(new byte[1])).join().length);
    }
}
