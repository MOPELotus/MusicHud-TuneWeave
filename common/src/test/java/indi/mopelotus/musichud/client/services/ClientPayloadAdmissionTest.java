package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.QueueItem;
import indi.mopelotus.musichud.interfaces.IClientMusicService;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;
import indi.mopelotus.musichud.network.*;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ServerPayloadFragment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ClientPayloadAdmissionTest {
    @Test void platformQueueEnteringConfigurationDoesNotReadPlayerOrCaptureAdmission() {
        Fixture f = new Fixture();
        Object origin = f.connection;
        Runnable queued = () -> ClientPayloadAdmission.receiveFromListener(origin, f.connection,
                () -> { throw new AssertionError("Player is unavailable in CONFIGURATION"); },
                player -> { throw new AssertionError("Must not capture configuration traffic"); },
                player -> fail("Must not deliver configuration traffic"));
        f.connection = null; f.player = null;
        queued.run();
        assertTrue(f.published.isEmpty());
    }

    @Test void oldPlatformListenerCannotAdoptReplacementPlayerBeforeAdmissionCapture() {
        Fixture f = new Fixture();
        Object oldListener = f.connection;
        Runnable queued = () -> f.deliverFromListener(oldListener, message(1));
        // The proxy TCP connection and logical player UUID may be unchanged. PLAY listener identity is not.
        f.connection = new String("connection"); f.player = new String("player");
        queued.run(); f.drain();
        assertTrue(f.published.isEmpty());
        f.deliverFromListener(f.connection, message(2)); f.drain();
        assertEquals(List.of(2), f.published, "The new PLAY listener can publish its resync queue");
    }

    @Test void platformListenerStillRequiresPlayerAndRechecksAdmissionAtWorkerPublication() {
        Fixture f = new Fixture();
        Object origin = f.connection;
        f.player = null; f.deliverFromListener(origin, message(1)); f.drain();
        assertTrue(f.published.isEmpty());
        f.player = new Object(); f.deliverFromListener(origin, message(2));
        assertEquals(1, f.tasks.size());
        f.connection = new Object(); f.player = new Object(); f.drain();
        assertTrue(f.published.isEmpty(), "The outer guard must preserve the existing worker admission");
        f.deliverFromListener(f.connection, message(3)); f.drain();
        assertEquals(List.of(3), f.published);
    }

    @Test void realQueueReceiverSeparatesRemoteLocalAndIntegratedTraffic() {
        Fixture f = new Fixture();
        f.deliver(true, message(1)); f.deliver(false, message(2)); f.drain();
        assertEquals(List.of(1), f.published);
        f.transition(ConnectionMode.ISOLATED, ConnectStatus.INCOMPATIBLE);
        f.deliver(true, message(3)); f.deliver(false, message(4)); f.drain();
        assertEquals(List.of(1, 4), f.published);
        f.transition(ConnectionMode.EXTERNAL, ConnectStatus.CONNECTED);
        f.integrated = true;
        f.deliver(false, message(5)); f.drain();
        assertEquals(List.of(1, 4, 5), f.published);
    }

    @Test void queuedRemoteMutationCannotOverwriteLocalQueueAfterTimeoutOrRefusal() {
        Fixture f = new Fixture();
        f.deliver(true, message(10));
        f.transition(ConnectionMode.ISOLATED, ConnectStatus.INCOMPATIBLE);
        f.deliver(false, message(11));
        f.drain();
        assertEquals(List.of(11), f.published);
        f.deliver(true, message(12));
        f.drain();
        assertEquals(List.of(11), f.published);
    }

    @Test void isolatedReconnectInvalidatesLocalWorkAndDisconnectedClientsRejectEverything() {
        Fixture f = new Fixture();
        f.transition(ConnectionMode.ISOLATED, ConnectStatus.NOT_CONNECTED);
        f.deliver(false, message(1));
        f.transition(ConnectionMode.EXTERNAL, ConnectStatus.CONNECTED);
        f.deliver(true, message(2)); f.drain();
        assertEquals(List.of(2), f.published);
        f.deliver(true, message(3));
        f.transition(ConnectionMode.DISCONNECTED, ConnectStatus.NOT_CONNECTED);
        f.deliver(false, message(4)); f.deliver(true, message(5)); f.drain();
        assertEquals(List.of(2), f.published);
    }

    @Test void connectionPlayerAndAttemptIdentityAreCheckedAgainAtActualPublication() {
        Fixture f = new Fixture();
        f.deliver(true, message(1));
        f.connection = new String("connection"); f.drain();
        assertTrue(f.published.isEmpty());
        f.deliver(true, message(2));
        f.player = new String("player"); f.drain();
        assertTrue(f.published.isEmpty());
        f.deliver(true, message(3));
        f.transition(ConnectionMode.EXTERNAL, ConnectStatus.CONNECTED); f.drain();
        assertTrue(f.published.isEmpty(), "Same-object reentry still has a new generation");
    }

    @Test void fragmentedRealQueueRetainsAdmissionAcrossDecodeAndWorkerDispatch() {
        Fixture f = new Fixture();
        PayloadFragments.resetClient();
        PayloadFragments.register(RefreshMusicQueueMessage.class, RefreshMusicQueueMessage.CODEC, f.receiver, false);
        List<S2CPayload> sent = new ArrayList<>();
        PayloadFragments.sendS2C(message(1_000), sent::add);
        assertTrue(sent.size() > 1);
        try {
            for (S2CPayload payload : sent) f.deliverFragment(true, (ServerPayloadFragment) payload);
            assertEquals(1, f.tasks.size(), "The real decoded queue receiver scheduled its mutation");
            f.transition(ConnectionMode.ISOLATED, ConnectStatus.INCOMPATIBLE);
            PayloadFragments.resetClient();
            f.drain();
            assertTrue(f.published.isEmpty());
            sent.clear();
            PayloadFragments.sendS2C(message(1_001), sent::add);
            for (S2CPayload payload : sent) f.deliverFragment(true, (ServerPayloadFragment) payload);
            assertTrue(f.tasks.isEmpty(), "Remote fragments cannot enter an isolated client");
            for (S2CPayload payload : sent) f.deliverFragment(false, (ServerPayloadFragment) payload);
            f.drain();
            assertEquals(List.of(1_001), f.published);
        } finally { PayloadFragments.resetClient(); }
    }

    @Test void handshakeIsTheOnlyPendingExceptionAndUnscopedWorkIsRejected() {
        Fixture f = new Fixture();
        f.transition(ConnectionMode.EXTERNAL, ConnectStatus.NOT_CONNECTED);
        assertTrue(f.admission.capture(true, f.player, true).isCurrent());
        assertFalse(f.admission.capture(true, f.player, false).isCurrent());
        f.receiver.receive(message(1), f.peer);
        assertTrue(f.tasks.isEmpty());
        f.transition(ConnectionMode.ISOLATED, ConnectStatus.NOT_CONNECTED);
        assertTrue(f.admission.capture(true, f.player, true).isCurrent(), "Only the handshake controller can use this for retired cleanup");
        assertFalse(f.admission.capture(true, f.player, false).isCurrent());
    }

    @Test void resolverCanWorkOutsideLockButLateResultCannotReachReplacementConnection() {
        Fixture f = new Fixture();
        ClientPacketContext.Admission[] captured = new ClientPacketContext.Admission[1];
        ClientPacketContext.receive(f.admission.capture(true, f.player, false),
                () -> captured[0] = ClientPacketContext.capture());
        assertTrue(captured[0].isCurrent());
        assertFalse(Thread.holdsLock(f.lock)); // Blocking resolution happens here, outside the transition lock.
        f.transition(ConnectionMode.ISOLATED, ConnectStatus.INCOMPATIBLE);
        captured[0].runIfCurrent(() -> fail("Late resolver result would be routed into the local server"));
        assertFalse(ClientPacketContext.capture().isCurrent(), "Transport context is not left on the thread");
    }

    @Test void olderQueueWorkerCannotRestoreTrackAfterNewerEmptyQueue() {
        Fixture f = new Fixture();
        f.deliver(true, message(1)); f.deliver(true, message(0));
        Collections.reverse(f.tasks); f.drain();
        assertEquals(List.of(0), f.published, "Latest received snapshot must win even when workers run backwards");
    }

    @Test void queuedPublicationRetainsTheReceivedQueueSnapshot() {
        Fixture f = new Fixture();
        var message = message(1); f.deliver(true, message);
        message.queue().clear(); f.drain();
        assertEquals(List.of(1), f.published, "The queued task owns a snapshot, not the caller's mutable queue");
    }

    private static RefreshMusicQueueMessage message(int count) {
        Queue<QueueItem> items = new ArrayDeque<>();
        for (int i = 0; i < count; i++) items.add(new QueueItem(MusicDetail.NONE, new UUID(0, i + 1)));
        return new RefreshMusicQueueMessage(items);
    }

    private static final class Fixture {
        final Object lock = new Object();
        Object connection = new String("connection"), player = new String("player");
        int generation;
        ConnectionMode mode = ConnectionMode.EXTERNAL;
        ConnectStatus status = ConnectStatus.CONNECTED;
        boolean integrated;
        final List<Runnable> tasks = new ArrayList<>();
        final List<Integer> published = new ArrayList<>();
        final indi.mopelotus.musichud.client.services.music.states.QueueSnapshotPublication queuePublication =
                new indi.mopelotus.musichud.client.services.music.states.QueueSnapshotPublication(lock, queue -> {
                    assertTrue(Thread.holdsLock(lock)); published.add(queue.size());
                });
        final ClientPayloadAdmission admission = new ClientPayloadAdmission(lock, () -> generation,
                () -> connection, () -> player, () -> mode, () -> status, () -> integrated, () -> true);
        final IPlayerClient peer = new IPlayerClient() {
            public UUID getUUID() { return new UUID(0, 1); }
            public String getName() { return "listener"; }
            public ClientType getClientType() { return ClientType.LOCAL; }
        };
        final IClientMusicService music = (IClientMusicService) Proxy.newProxyInstance(
                IClientMusicService.class.getClassLoader(), new Class<?>[]{IClientMusicService.class}, (proxy, method, args) -> {
                    assertEquals("prepareQueueRefresh", method.getName());
                    assertTrue(Thread.holdsLock(lock), "Queue ordering is captured during admitted receipt");
                    @SuppressWarnings("unchecked") Queue<QueueItem> queue = (Queue<QueueItem>) args[0];
                    return queuePublication.prepare(queue);
                });
        final NetworkReceiver<RefreshMusicQueueMessage> receiver = RefreshMusicQueueMessage.receiver(() -> music, tasks::add);
        void deliver(boolean remote, RefreshMusicQueueMessage message) {
            ClientPacketContext.receive(admission.capture(remote, player, false), () -> receiver.receive(message, peer));
        }
        void deliverFromListener(Object listener, RefreshMusicQueueMessage message) {
            ClientPayloadAdmission.receiveFromListener(listener, connection, () -> player,
                    originPlayer -> admission.capture(true, originPlayer, false),
                    originPlayer -> receiver.receive(message, peer));
        }
        void deliverFragment(boolean remote, ServerPayloadFragment fragment) {
            ClientPacketContext.receive(admission.capture(remote, player, false), () -> PayloadFragments.receive(fragment.frame(), peer, false));
        }
        void transition(ConnectionMode next, ConnectStatus nextStatus) {
            synchronized (lock) { generation++; mode = next; status = nextStatus; }
        }
        void drain() { List.copyOf(tasks).forEach(Runnable::run); tasks.clear(); }
    }
}
