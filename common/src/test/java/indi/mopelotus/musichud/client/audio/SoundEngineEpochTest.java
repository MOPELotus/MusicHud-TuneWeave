package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SoundEngineEpochTest {
    @Test void reloadWaitsForActiveDeviceOperationThenInvalidatesBeforeReuse() throws Exception {
        SoundEngineEpoch.ready();
        long before = SoundEngineEpoch.generation();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), reloading = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> operation = executor.submit(() -> SoundEngineEpoch.guarded(() -> {
                entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                assertEquals(before, SoundEngineEpoch.generation());
                return null;
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<?> reload = executor.submit(() -> { reloading.countDown(); SoundEngineEpoch.invalidate(); });
            assertTrue(reloading.await(1, TimeUnit.SECONDS));
            assertFalse(reload.isDone());
            release.countDown(); operation.get(2, TimeUnit.SECONDS); reload.get(2, TimeUnit.SECONDS);
            assertFalse(SoundEngineEpoch.available());
            assertEquals(before + 1, SoundEngineEpoch.generation());
        } finally { release.countDown(); SoundEngineEpoch.ready(); }
    }
}
