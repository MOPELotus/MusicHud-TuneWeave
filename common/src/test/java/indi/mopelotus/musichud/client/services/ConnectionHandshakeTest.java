package indi.mopelotus.musichud.client.services;

import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;
import indi.mopelotus.musichud.network.ProtocolInfo;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionHandshakeTest {
    @Test void compatibleResponseLeavesServerMembershipIntactAndInitializesExternalStateOnce() {
        for (boolean allowIsolated : List.of(false, true)) {
            for (ConnectionMode previous : List.of(ConnectionMode.DISCONNECTED, ConnectionMode.ISOLATED)) {
                Fixture fixture = new Fixture(allowIsolated);
                fixture.mode = previous;
                fixture.handshake.begin();
                fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
                fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
                assertTrue(fixture.events.isEmpty(), "No worker-thread state changes");
                fixture.drain();
                assertEquals(ConnectionMode.EXTERNAL, fixture.mode);
                assertEquals(ConnectStatus.CONNECTED, fixture.status);
                assertEquals(List.of("reset", "mode:EXTERNAL", "status:CONNECTED", "restore", "initial:EXTERNAL", "refresh"), fixture.events);
                assertEquals(1, fixture.initialStates);
                assertEquals(0, fixture.localJoins);
                assertFalse(fixture.events.contains("leave-remote"));
            }
        }
    }

    @Test void explicitRefusalAndAcceptedButIncompatibleProtocolsHonorBothFallbackPolicies() {
        for (ConnectResponse response : List.of(ConnectResponse.current(false),
                new ConnectResponse(true, "music_hud", Version.CURRENT, ProtocolInfo.CAPABILITIES),
                new ConnectResponse(true, ProtocolInfo.PROJECT_ID, Version.CURRENT, Set.of()))) {
            for (boolean allowIsolated : List.of(false, true)) {
                Fixture fixture = new Fixture(allowIsolated);
                fixture.mode = ConnectionMode.EXTERNAL;
                fixture.handshake.begin();
                fixture.handshake.receive(response, fixture.player);
                fixture.drain();
                assertEquals(ConnectStatus.INCOMPATIBLE, fixture.status);
                assertEquals(allowIsolated ? ConnectionMode.ISOLATED : ConnectionMode.DISCONNECTED, fixture.mode);
                assertEquals(allowIsolated ? 1 : 0, fixture.initialStates);
                assertEquals(allowIsolated ? 1 : 0, fixture.localJoins);
                assertTrue(fixture.events.contains("refresh"));
                if (allowIsolated) assertTrue(fixture.events.contains("initial:ISOLATED"));
            }
        }
    }

    @Test void timeoutOrManualIsolationRejectsQueuedAndSubsequentLateResponses() {
        Fixture fixture = new Fixture(true);
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.handshake.isolate();
        fixture.events.clear();
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.drain();
        assertEquals(List.of("leave-remote"), fixture.events);
        assertEquals(ConnectionMode.ISOLATED, fixture.mode);
        assertEquals(ConnectStatus.NOT_CONNECTED, fixture.status);
    }

    @Test void disconnectAndSameObjectReentryInvalidateAlreadyQueuedResponses() {
        Fixture fixture = new Fixture(true);
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(false), fixture.player);
        fixture.handshake.invalidate();
        fixture.handshake.begin(); // New local attempt even when the player and connection objects are unchanged.
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.drain();
        assertEquals(ConnectStatus.CONNECTED, fixture.status);
        assertEquals(ConnectionMode.EXTERNAL, fixture.mode);
        assertEquals(1, fixture.initialStates);
        assertEquals(0, fixture.localJoins);
    }

    @Test void physicalReplacementAndOldPlayerIdentityAreRejectedBeforeAndAfterDispatch() {
        Fixture fixture = new Fixture(true);
        Object oldPlayer = fixture.player;
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(false), fixture.player);
        fixture.connection = new String("connection");
        fixture.drain();
        assertTrue(fixture.events.isEmpty());
        fixture.player = new String("player");
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(false), oldPlayer);
        assertTrue(fixture.queue.isEmpty(), "Equal-valued objects are not the same player");
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.player = new String("player");
        fixture.drain();
        assertTrue(fixture.events.isEmpty());
    }

    @Test void disconnectedDisabledOrUnrequestedResponsesCannotChangeState() {
        Fixture fixture = new Fixture(true);
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        assertTrue(fixture.queue.isEmpty());
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.enabled = false;
        fixture.drain();
        assertTrue(fixture.events.isEmpty());
        fixture.enabled = true;
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(false), fixture.player);
        fixture.connection = null;
        fixture.drain();
        assertTrue(fixture.events.isEmpty());
    }

    @Test void reconnectingFromRejectedIsolationCanBecomeExternalAndIgnoresTheOldResponse() {
        Fixture fixture = new Fixture(true);
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(false), fixture.player);
        fixture.drain();
        assertEquals(ConnectionMode.ISOLATED, fixture.mode);
        fixture.handshake.receive(ConnectResponse.current(false), fixture.player);
        assertTrue(fixture.queue.isEmpty());
        fixture.handshake.begin();
        fixture.handshake.receive(ConnectResponse.current(true), fixture.player);
        fixture.drain();
        assertEquals(ConnectionMode.EXTERNAL, fixture.mode);
        assertEquals(ConnectStatus.CONNECTED, fixture.status);
        assertEquals(2, fixture.initialStates);
    }

    @Test void diagnosticUsesActualModeAndExistingTranslationsInEveryLanguage() throws Exception {
        String plain = ConnectionHandshake.incompatibleMessageKey(ConnectionMode.DISCONNECTED);
        String isolated = ConnectionHandshake.incompatibleMessageKey(ConnectionMode.ISOLATED);
        assertEquals(plain, ConnectionHandshake.incompatibleMessageKey(ConnectionMode.EXTERNAL));
        assertNotEquals(plain, isolated);
        for (String language : List.of("en_us", "zh_cn", "zh_tw", "zh_hk")) {
            try (var input = getClass().getResourceAsStream("/assets/musichud_tuneweave/lang/" + language + ".json")) {
                assertNotNull(input);
                var translations = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(translations.has(plain));
                assertTrue(translations.has(isolated));
            }
        }
    }

    private static final class Fixture implements ConnectionHandshake.Effects {
        final ConnectionHandshake handshake = new ConnectionHandshake(this, this);
        final List<Runnable> queue = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Object connection = new String("connection"), player = new String("player");
        ConnectionMode mode = ConnectionMode.DISCONNECTED;
        ConnectStatus status = ConnectStatus.NOT_CONNECTED;
        boolean enabled = true;
        final boolean isolatedAllowed;
        int initialStates, localJoins;
        Fixture(boolean isolatedAllowed) { this.isolatedAllowed = isolatedAllowed; }
        public Object connection() { return connection; }
        public Object player() { return player; }
        public boolean enabled() { return enabled; }
        public boolean allowIsolated() { return isolatedAllowed; }
        public void execute(Runnable action) { queue.add(action); }
        public void mode(ConnectionMode mode) { this.mode = mode; events.add("mode:" + mode); }
        public void status(ConnectStatus status) { this.status = status; events.add("status:" + status); }
        public void resetPlayback() { events.add("reset"); }
        public void joinLocalPlayer() { localJoins++; events.add("join-local"); }
        public void restoreSession() { events.add("restore"); }
        public void requestInitialState() { initialStates++; events.add("initial:" + mode); }
        public void refreshGui() { events.add("refresh"); }
        public void leaveRemoteServer() { events.add("leave-remote"); }
        void drain() { List.copyOf(queue).forEach(Runnable::run); queue.clear(); }
    }
}
