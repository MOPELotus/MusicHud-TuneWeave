package indi.mopelotus.musichud.network;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BackendDeploymentResponderTest {
    @Test void onlyDedicatedRequestsReplyAndPresenceNeverLoops() {
        for (boolean dedicated : new boolean[]{false, true}) for (boolean request : new boolean[]{false, true}) {
            AtomicInteger replies = new AtomicInteger();
            BackendDeploymentResponder.respond(dedicated, request, () -> true, Runnable::run, replies::incrementAndGet);
            assertEquals(dedicated && request ? 1 : 0, replies.get());
        }
    }
    @Test void executorShutdownCannotReplyOrEscapeTheNetworkHandler() {
        assertDoesNotThrow(() -> BackendDeploymentResponder.respond(true, true, () -> true,
                task -> { throw new java.util.concurrent.RejectedExecutionException(); },
                () -> fail("Stopped executor must not reply")));
    }
    @Test void delayedReplyCannotReachRetiredPeerOrStoppedServer() {
        var tasks = new ArrayList<Runnable>();
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger replies = new AtomicInteger();
        BackendDeploymentResponder.respond(true, true, live::get, tasks::add, replies::incrementAndGet);
        live.set(false);
        tasks.forEach(Runnable::run);
        assertEquals(0, replies.get());
        tasks.clear();
        BackendDeploymentResponder.respond(true, true, live::get, tasks::add, replies::incrementAndGet);
        assertTrue(tasks.isEmpty());
    }
}
