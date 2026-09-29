package indi.mopelotus.musichud.platform.plugin.paper.network;

import indi.mopelotus.musichud.network.DeferredPluginSend;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentReplyTest {
    @Test void registeredPeerGetsPresenceWithoutWaitingForAnEntityTick() {
        var sent = new AtomicInteger();
        var reply = new DeferredPluginSend(() -> true, () -> true, sent::incrementAndGet,
                task -> fail("An already registered peer must not depend on a future entity tick"), 40);
        reply.run(); reply.run();
        assertEquals(1, sent.get());
    }

    @Test void retiredConnectionCannotSendAfterDelayedChannelRegistration() {
        var active = new AtomicBoolean(true);
        var registered = new AtomicBoolean(false);
        var pending = new ArrayDeque<Runnable>();
        var reply = new DeferredPluginSend(active::get, registered::get,
                () -> fail("Retired peer must not receive presence"), pending::add, 40);
        reply.run();
        active.set(false); registered.set(true);
        pending.remove().run();
        assertTrue(pending.isEmpty());
    }
}
