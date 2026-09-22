package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LatestMediaArtworkTest {
    @TempDir Path directory;

    @Test void rapidSwitchKeepsTheCurrentFileAndDeletesLateDownloadsOnly() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var owner = new LatestMediaArtwork(queue::addLast);
        var a = new CompletableFuture<Path>(); var b = new CompletableFuture<Path>();
        var shown = new ArrayList<Path>();
        Path unrelated = Files.writeString(directory.resolve("user-file.png"), "user");
        Path first = Files.writeString(directory.resolve("first.png"), "a");
        Path second = Files.writeString(directory.resolve("second.png"), "b");
        owner.load(() -> a, () -> true, shown::add);
        owner.reset(); owner.load(() -> b, () -> true, shown::add);
        b.complete(second); a.complete(first);
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertEquals(List.of(second), shown);
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second));
        owner.reset(); assertFalse(Files.exists(second)); assertTrue(Files.exists(unrelated));
    }

    @Test void stopBeforeQueuedDeliveryDiscardsArtworkWithoutPublishing() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var owner = new LatestMediaArtwork(queue::addLast);
        var current = new AtomicBoolean(true);
        Path file = Files.writeString(directory.resolve("pending.png"), "art");
        owner.load(() -> CompletableFuture.completedFuture(file), current::get, path -> fail("stale artwork"));
        current.set(false); queue.removeFirst().run();
        assertFalse(Files.exists(file));
    }

    @Test void rejectedDeliveryDeletesOnlyItsOwnedTemporaryFile() throws Exception {
        var owner = new LatestMediaArtwork(task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        Path file = Files.writeString(directory.resolve("rejected.png"), "art");
        owner.load(() -> CompletableFuture.completedFuture(file), () -> true, path -> fail("executor closed"));
        assertFalse(Files.exists(file));
    }
}
