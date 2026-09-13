package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.utils.ServerConnectionControlQueue;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ServerPlayerRegistryRaceTest {
    private final ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();

    @Test void physicalQuitDuringAlreadyAdmittedConnectCannotReinsertOfflineMember() throws Exception {
        Peer peer = new Peer(UUID.randomUUID());
        CountDownLatch admitted = new CountDownLatch(1), resume = new CountDownLatch(1), finished = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            NetworkReceiver<ConnectRequest> receiver = ServerDataPacketVThreadExecutor.controlReceiver(
                    new ServerConnectionControlQueue(executor), (request, origin) -> {
                        admitted.countDown(); await(resume);
                        registry.join(origin); finished.countDown();
                    });
            receiver.receive(ConnectRequest.current(), peer);
            await(admitted);
            // Some vanilla wrappers still report connected during the quit callback.
            registry.disconnect(peer);
            assertTrue(peer.isConnected());
            resume.countDown(); await(finished);
            assertNull(registry.sessionToken(peer.id));
        } finally { resume.countDown(); registry.leave(peer); }
    }

    @Test void disconnectAfterValidationCannotSlipBetweenMapCheckAndWrite() throws Exception {
        Peer peer = new Peer(UUID.randomUUID());
        CountDownLatch validated = new CountDownLatch(1), resume = new CountDownLatch(1), leaving = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        peer.online = () -> {
            if (probes.incrementAndGet() == 1) {
                boolean observed = peer.connected;
                validated.countDown(); await(resume);
                return observed;
            }
            return peer.connected;
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> join = executor.submit(() -> registry.join(peer));
            await(validated); peer.connected = false;
            Future<?> quit = executor.submit(() -> { leaving.countDown(); registry.disconnect(peer); });
            await(leaving); resume.countDown();
            join.get(5, TimeUnit.SECONDS); quit.get(5, TimeUnit.SECONDS);
            assertNull(registry.sessionToken(peer.id));
        } finally { resume.countDown(); registry.leave(peer); }
    }

    @Test void postWriteRollbackUsesObjectIdentityAndCannotDeleteEqualUuidReplacement() throws Exception {
        Peer old = new Peer(UUID.randomUUID()), replacement = new Peer(old.id);
        CountDownLatch checkingWrite = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        old.online = () -> {
            if (probes.incrementAndGet() == 2) { checkingWrite.countDown(); await(resume); }
            return old.connected;
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> join = executor.submit(() -> registry.join(old));
            await(checkingWrite);
            assertSame(old, registry.sessionToken(old.id));
            registry.join(replacement);
            assertEquals(old, replacement, "Platform wrappers compare UUID rather than physical connection");
            old.connected = false; resume.countDown(); join.get(5, TimeUnit.SECONDS);
            assertSame(replacement, registry.sessionToken(old.id));
        } finally { resume.countDown(); registry.leave(old); registry.leave(replacement); }
    }

    @Test void staleOfflineJoinNeverOverwritesAnAlreadyRegisteredReplacement() {
        Peer old = new Peer(UUID.randomUUID()), replacement = new Peer(old.id);
        try {
            registry.join(replacement);
            old.connected = false;
            registry.join(old);
            assertSame(replacement, registry.sessionToken(old.id));
        } finally { registry.leave(old); registry.leave(replacement); }
    }

    @Test void protocolLeaveAllowsRejoinButPhysicalQuitRejectsAllWrappersOfOnlyThatIdentity() {
        Peer old = new Peer(UUID.randomUUID()), sameConnection = new Peer(old.id), replacement = new Peer(old.id);
        sameConnection.identity = old.identity;
        try {
            registry.join(old); registry.leave(old); registry.join(old);
            assertSame(old, registry.sessionToken(old.id));
            registry.disconnect(old);
            registry.join(sameConnection);
            assertNull(registry.sessionToken(old.id), "A stale/newly cached wrapper cannot reopen a physically closed peer");
            registry.join(replacement);
            assertSame(replacement, registry.sessionToken(old.id));
        } finally { registry.leave(old); registry.leave(replacement); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled race did not reach its boundary"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static final class Peer implements IPlayerClient {
        final UUID id;
        Object identity = new Object();
        volatile boolean connected = true;
        BooleanSupplier online = () -> connected;
        Peer(UUID id) { this.id = id; }
        public UUID getUUID() { return id; }
        public String getName() { return "race"; }
        public ClientType getClientType() { return ClientType.REMOTE; }
        public boolean isConnected() { return online.getAsBoolean(); }
        public Object connectionIdentity() { return identity; }
        public boolean equals(Object other) { return other instanceof Peer peer && id.equals(peer.id); }
        public int hashCode() { return id.hashCode(); }
    }
}
