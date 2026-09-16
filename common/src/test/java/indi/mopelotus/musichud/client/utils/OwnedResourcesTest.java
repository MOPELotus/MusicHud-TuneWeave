package indi.mopelotus.musichud.client.utils;

import indi.mopelotus.musichud.client.ui.ImagePublication;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OwnedResourcesTest {
    private static final class Resource implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }

    private static OwnedResources scope() {
        return new OwnedResources(error -> fail("Unexpected close failure", error));
    }

    @Test void shutdownOwnsCachedAndEvictedResourcesIncludingAQueuedCleanerRelease() {
        var resources = scope();
        Resource cached = resources.create(Resource::new);
        Resource evicted = resources.create(Resource::new);
        List<Runnable> renderQueue = new ArrayList<>();
        renderQueue.add(() -> resources.release(evicted));
        resources.close();
        renderQueue.forEach(Runnable::run);
        resources.release(cached);
        resources.close();
        assertEquals(1, cached.closes);
        assertEquals(1, evicted.closes);
    }

    @Test void pendingUploadIsCancelledBeforeItsBitmapCanBeUsedAfterShutdown() {
        var resources = scope();
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger allocations = new AtomicInteger();
        var future = resources.submit(queue::add, () -> {
            allocations.incrementAndGet();
            return resources.create(Resource::new);
        });
        resources.close();
        assertThrows(CancellationException.class, future::join);
        queue.forEach(Runnable::run);
        assertEquals(0, allocations.get());
        assertThrows(CancellationException.class, () -> resources.create(() -> { fail(); return null; }));
    }

    @Test void stopKeepsExistingUiImagesAliveUntilDrawingEndsAndRejectsNewUploads() {
        var resources = scope();
        Resource image = resources.create(Resource::new);
        resources.stop();
        assertEquals(0, image.closes);
        assertTrue(resources.isStopped());
        var rejected = resources.submit(task -> fail("Must not enqueue after stop"), Resource::new);
        assertThrows(CancellationException.class, rejected::join);
        resources.close();
        assertEquals(1, image.closes);
    }

    @Test void failingResourceDoesNotPreventOtherResourcesFromBeingReleased() {
        List<Exception> errors = new ArrayList<>();
        var resources = new OwnedResources(errors::add);
        resources.create(() -> (AutoCloseable) () -> { throw new IllegalStateException("failed close"); });
        Resource other = resources.create(Resource::new);
        resources.close();
        resources.close();
        assertEquals(1, other.closes);
        assertEquals(1, errors.size());
    }

    @Test void lateDetachAndQueuedUiPublicationReleaseTemporaryTextureExactlyOnce() {
        var resources = scope();
        List<Runnable> ui = new ArrayList<>(), render = new ArrayList<>();
        var publication = new ImagePublication<Resource>(ui::add, resource -> render.add(() -> resources.release(resource)));
        Resource temporary = resources.create(Resource::new);
        publication.complete(publication.next(), temporary, null, true,
                image -> fail("Detached view must not upload"), error -> fail());
        publication.next(); // View detached while its completion was queued.
        resources.close();
        ui.forEach(Runnable::run);
        render.forEach(Runnable::run);
        assertEquals(1, temporary.closes);
    }

    @Test void rejectedQueueAndFailedAllocationCompleteExceptionally() {
        var resources = scope();
        var rejected = resources.submit(task -> { throw new RejectedExecutionException(); }, Resource::new);
        assertInstanceOf(RejectedExecutionException.class, assertThrows(java.util.concurrent.CompletionException.class, rejected::join).getCause());
        var failed = resources.submit(Runnable::run, () -> { throw new IllegalStateException(); });
        assertTrue(failed.isCompletedExceptionally());
        resources.close();
    }

    @Test void shutdownStillDeliversAnOwnedCpuCopyWhenItsTaskWasAlreadyRunning() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 50; attempt++) {
                var resources = scope();
                CountDownLatch copying = new CountDownLatch(1), finishCopy = new CountDownLatch(1), stopping = new CountDownLatch(1);
                var copy = resources.submit(executor, () -> {
                    copying.countDown();
                    try { assertTrue(finishCopy.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    return new Resource(); // Native CPU copy transferred to the caller, not a shared GPU texture.
                });
                assertTrue(copying.await(5, TimeUnit.SECONDS));
                var stop = executor.submit(() -> { stopping.countDown(); resources.close(); });
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                finishCopy.countDown();
                stop.get(5, TimeUnit.SECONDS);
                Resource delivered = copy.get(5, TimeUnit.SECONDS);
                assertEquals(0, delivered.closes);
                delivered.close();
            }
        }
    }

    @Test void shutdownCannotRacePixelReadOrAnAllocationAlreadyInProgress() throws Exception {
        var resources = scope();
        Resource texture = resources.create(Resource::new);
        CountDownLatch reading = new CountDownLatch(1), finishRead = new CountDownLatch(1), closing = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var read = executor.submit(() -> resources.access(() -> {
                reading.countDown();
                try { assertTrue(finishRead.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                assertEquals(0, texture.closes);
                return resources.create(Resource::new);
            }));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            var close = executor.submit(() -> { closing.countDown(); resources.close(); });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            finishRead.countDown();
            Resource allocated = read.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(1, texture.closes);
            assertEquals(1, allocated.closes);
        } finally {
            finishRead.countDown();
        }
    }
}
