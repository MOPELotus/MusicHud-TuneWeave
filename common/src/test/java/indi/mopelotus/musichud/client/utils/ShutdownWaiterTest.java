package indi.mopelotus.musichud.client.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShutdownWaiterTest {
    @Test void wakesBothAnOutstandingFrameAndALateFinalTraversal() throws Exception {
        Object frameLock = new Object();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger completedFrames = new AtomicInteger();
        Thread ui = new Thread(() -> {
            synchronized (frameLock) {
                ready.countDown();
                try {
                    frameLock.wait();
                    completedFrames.incrementAndGet();
                    // A queued traversal may publish one last frame before the delayed quit message.
                    frameLock.wait();
                    completedFrames.incrementAndGet();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ui.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(ShutdownWaiter.await(ui, frameLock, 1000));
            assertEquals(2, completedFrames.get());
        } finally {
            ui.interrupt();
            ui.join(1000);
        }
    }

    @Test void unrelatedBlockedThreadTimesOutAndAlreadyFinishedThreadReturnsImmediately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread ui = new Thread(() -> {
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        ui.start();
        try {
            assertFalse(ShutdownWaiter.await(ui, new Object(), 10));
        } finally {
            release.countDown();
            ui.join(1000);
        }
        assertTrue(ShutdownWaiter.await(ui, null, 0));
    }

    @Test void interruptionIsPreserved() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread ui = new Thread(() -> {
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        ui.start();
        try {
            Thread.currentThread().interrupt();
            assertFalse(ShutdownWaiter.await(ui, null, 1000));
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            ui.join(1000);
        }
    }
}
