package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerPacketAdmissionTest {
    @Test void requiresHandshakeAndRejectsQueuedPacketsFromOldMembership() {
        var registry = ServerPlayerRegistry.getInstance();
        var first = new Player(UUID.randomUUID());
        C2SPayload action = new C2SPayload() {};
        try {
            assertFalse(ServerPacketAdmission.capture(action, first).allowed());
            assertTrue(ServerPacketAdmission.capture(ConnectRequest.current(), first).allowed());
            registry.join(first);
            var pending = ServerPacketAdmission.capture(action, first);
            assertTrue(pending.allowed());
            registry.leave(first);
            var replacement = new Player(first.id); registry.join(replacement);
            assertFalse(pending.allowed());
            assertTrue(ServerPacketAdmission.capture(action, replacement).allowed());
            replacement.connected = false;
            assertFalse(ServerPacketAdmission.capture(ConnectRequest.current(), replacement).allowed());
        } finally {
            Object current = registry.sessionToken(first.id);
            if (current instanceof IPlayerClient player) registry.leave(player);
        }
    }

    @Test void channelNamesAreIdenticalUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("musichud_tuneweave:connect_request", ProtocolChannels.id(ConnectRequest.class));
            assertEquals("musichud_tuneweave:initial_identity", ProtocolChannels.id(InitialIdentity.class));
        } finally { Locale.setDefault(previous); }
    }
    private static class InitialIdentity {}
    private static class Player implements IPlayerClient {
        final UUID id; boolean connected = true;
        Player(UUID id) { this.id = id; }
        public UUID getUUID() { return id; }
        public String getName() { return "Player"; }
        public ClientType getClientType() { return ClientType.REMOTE; }
        public boolean isConnected() { return connected; }
    }
}
