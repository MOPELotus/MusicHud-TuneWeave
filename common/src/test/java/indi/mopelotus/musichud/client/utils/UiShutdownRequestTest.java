package indi.mopelotus.musichud.client.utils;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UiShutdownRequestTest {
    static class ModernHost {
        boolean shutdownRequested;
        final ArrayDeque<Runnable> messages = new ArrayDeque<>();
        int finishes;
        private void requestShutdown() {
            if (shutdownRequested) return;
            shutdownRequested = true;
            messages.add(() -> finishes++);
        }
    }
    static final class LoaderHost extends ModernHost {}

    @Test void nativeRequestSharesStateWithTheLaterHostDestroyAndFindsInheritedPrivateMethod() {
        var host = new LoaderHost();
        var request = new UiShutdownRequest();
        request.request(host, () -> fail("Native host must retain its own shutdown state"));
        ((ModernHost) host).requestShutdown();
        request.request(host, () -> fail("Repeated request must not dispatch again"));
        assertEquals(1, host.messages.size());
        host.messages.remove().run();
        assertEquals(1, host.finishes);
    }

    @Test void concurrentLegacyRequestsDispatchFinishOnce() throws Exception {
        var request = new UiShutdownRequest();
        var count = new AtomicInteger();
        var start = new CountDownLatch(1);
        Runnable action = () -> {
            try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            request.request(new Object(), count::incrementAndGet);
        };
        Thread a = new Thread(action), b = new Thread(action);
        a.start(); b.start(); start.countDown(); a.join(5000); b.join(5000);
        assertFalse(a.isAlive()); assertFalse(b.isAlive());
        assertEquals(1, count.get());
    }

    static class FailingHost {
        private void requestShutdown() { throw new IllegalStateException("host failure"); }
    }
    @Test void hostFailureIsPreservedWithoutDispatchingASecondLegacyFinish() {
        var failure = assertThrows(IllegalStateException.class,
                () -> new UiShutdownRequest().request(new FailingHost(), () -> fail("No duplicate fallback")));
        assertEquals("host failure", failure.getMessage());
    }
}
