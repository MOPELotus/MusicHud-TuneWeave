package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.DisconnectMessage;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.ServerConnectionControlQueue;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HandshakeRemoteRetirementTest {
    @Test void acceptingProductionTransitionPreservesTheActualRegisteredMember() {
        try (Fixture f = new Fixture(true)) {
            f.begin(); f.drainServer(); f.drainClient();
            assertSame(f.peer, f.registry.sessionToken(f.id));
            assertEquals(ConnectionMode.EXTERNAL, f.mode);
            assertEquals(0, f.leaves);
        }
    }

    @Test void timeoutBeforeConnectWorkerRunsStillRetiresMembershipUnderBothPolicies() {
        for (boolean isolated : List.of(false, true)) try (Fixture f = new Fixture(isolated)) {
            f.begin();
            assertNull(f.registry.sessionToken(f.id));
            f.handshake.timeout(); // Disconnect arrives while the peer has not joined yet.
            assertEquals(1, f.server.size(), "Connect and Disconnect share one worker");
            f.drainServer(); f.drainClient(); f.drainServer();
            assertNull(f.registry.sessionToken(f.id));
            assertEquals(isolated ? ConnectionMode.ISOLATED : ConnectionMode.DISCONNECTED, f.mode);
            assertEquals(ConnectStatus.NOT_CONNECTED, f.status);
            assertEquals(2, f.leaves, "Timeout retires immediately; the delayed accepted response gets one cleanup");
        }
    }

    @Test void queuedOldCleanupCannotRemoveNewAttemptUsingTheSamePhysicalConnection() {
        try (Fixture f = new Fixture(true)) {
            f.begin(); f.handshake.timeout(); f.drainServer();
            assertFalse(f.client.isEmpty(), "Retired acknowledgement cleanup is queued");
            f.begin();
            f.drainClient(); // Old cleanup runs after the new attempt starts.
            f.drainServer(); f.drainClient(); f.drainServer();
            assertSame(f.peer, f.registry.sessionToken(f.id));
            assertEquals(ConnectStatus.CONNECTED, f.status);
            assertEquals(1, f.leaves, "Old cleanup must not emit a second Disconnect after new Connect");
        }
    }

    @Test void disconnectInvalidatesQueuedRetirementAndPhysicalReplacementCannotBeCleaned() {
        try (Fixture f = new Fixture(true)) {
            f.begin(); f.handshake.timeout(); f.drainServer();
            f.handshake.invalidate(); f.connection = new Object();
            f.drainClient();
            assertEquals(1, f.leaves);
        }
    }

    private static final class Fixture implements ConnectionHandshake.Effects, AutoCloseable {
        final UUID id = UUID.randomUUID();
        final Object lock = new Object();
        Object connection = new Object(), player = new Object();
        final List<Runnable> server = new ArrayList<>(), client = new ArrayList<>();
        final ServerPlayerRegistry registry = ServerPlayerRegistry.getInstance();
        final IPlayerClient peer = new IPlayerClient() {
            public UUID getUUID() { return id; }
            public String getName() { return "retirement"; }
            public ClientType getClientType() { return ClientType.REMOTE; }
        };
        final ServerConnectionControlQueue control = new ServerConnectionControlQueue(server::add);
        final NetworkReceiver<DisconnectMessage> disconnect = ServerDataPacketVThreadExecutor.controlReceiver(control,
                (payload, origin) -> registry.leave(origin));
        final ConnectionHandshake handshake = new ConnectionHandshake(lock, this);
        final NetworkReceiver<ConnectRequest> connect = ServerDataPacketVThreadExecutor.controlReceiver(control,
                (payload, origin) -> { registry.join(origin); handshake.receive(ConnectResponse.current(true), player); });
        final boolean isolated;
        int leaves;
        ConnectionMode mode = ConnectionMode.EXTERNAL;
        ConnectStatus status = ConnectStatus.NOT_CONNECTED;
        Fixture(boolean isolated) { this.isolated = isolated; }
        void begin() { handshake.begin(); mode = ConnectionMode.EXTERNAL; status = ConnectStatus.NOT_CONNECTED; connect.receive(ConnectRequest.current(), peer); }
        void drainServer() { drain(server); }
        void drainClient() { drain(client); }
        static void drain(List<Runnable> tasks) { var copy = List.copyOf(tasks); tasks.clear(); copy.forEach(Runnable::run); }
        public Object connection() { return connection; }
        public Object player() { return player; }
        public boolean enabled() { return true; }
        public boolean allowIsolated() { return isolated; }
        public void execute(Runnable action) { client.add(action); }
        public void mode(ConnectionMode mode) { this.mode = mode; }
        public void status(ConnectStatus status) { this.status = status; }
        public void resetPlayback() {}
        public void joinLocalPlayer() {}
        public void restoreSession() {}
        public void requestInitialState() {}
        public void refreshGui() {}
        public void leaveRemoteServer() { assertTrue(Thread.holdsLock(lock)); leaves++; disconnect.receive(DisconnectMessage.INSTANCE, peer); }
        public void close() { registry.leave(peer); }
    }
}
