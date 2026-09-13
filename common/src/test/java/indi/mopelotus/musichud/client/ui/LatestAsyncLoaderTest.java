package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LatestAsyncLoaderTest {
    @Test void oldCompletionsAndAlreadyPostedUpdatesCannotReviveClearedContent() {
        var ui = new ArrayDeque<Runnable>(); var retry = new ArrayDeque<Runnable>(); var shown = new ArrayList<String>();
        var loader = new LatestAsyncLoader<String>(ui::add, retry::add, 3);
        var old = new CompletableFuture<String>();
        loader.load(() -> old, shown::add, error -> fail("Old failure published"));
        old.complete("old");
        loader.load(() -> CompletableFuture.completedFuture("new"), shown::add, error -> fail());
        while (!ui.isEmpty()) ui.removeFirst().run();
        assertEquals(List.of("new"), shown);
        loader.load(() -> CompletableFuture.completedFuture("cleared"), shown::add, error -> fail());
        loader.cancel(); while (!ui.isEmpty()) ui.removeFirst().run();
        assertEquals(List.of("new"), shown);
    }

    @Test void retriesAreBoundedAndCancelledRetryDoesNotStartAnotherRequest() {
        var retries = new ArrayDeque<Runnable>(); var calls = new AtomicInteger(); var failed = new AtomicInteger();
        var loader = new LatestAsyncLoader<String>(Runnable::run, retries::add, 3);
        loader.load(() -> { calls.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException()); },
                value -> fail(), error -> failed.incrementAndGet());
        while (!retries.isEmpty()) retries.removeFirst().run();
        assertEquals(3, calls.get()); assertEquals(1, failed.get());
        loader.load(() -> { calls.incrementAndGet(); throw new IllegalStateException(); }, value -> fail(), error -> fail());
        loader.cancel(); retries.removeFirst().run();
        assertEquals(4, calls.get());
    }
}
