package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import static indi.mopelotus.musichud.client.services.ClientConnectionRouting.Route.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionRoutingTest {
    @Test void integratedHandshakeAndInitialStateWorkWithEitherFallbackPolicy() {
        for (boolean isolated : List.of(false, true)) {
            assertEquals(LOCAL, ClientConnectionRouting.route(true, false, ConnectStatus.NOT_CONNECTED, true, isolated));
            assertEquals(LOCAL, ClientConnectionRouting.route(true, false, ConnectStatus.CONNECTED, false, isolated));
        }
    }
    @Test void remoteHandshakeUsesPhysicalTransportAndRefusedConnectionsHonorFallbackPolicy() {
        for (boolean isolated : List.of(false, true)) {
            assertEquals(REMOTE, ClientConnectionRouting.route(true, true, ConnectStatus.NOT_CONNECTED, true, isolated));
            assertEquals(REMOTE, ClientConnectionRouting.route(true, true, ConnectStatus.CONNECTED, false, isolated));
            assertEquals(isolated ? LOCAL : DROP, ClientConnectionRouting.route(true, true, ConnectStatus.INCOMPATIBLE, false, isolated));
        }
    }
    @Test void noPayloadIsSentAfterPlayerDetach() {
        for (boolean remote : List.of(false, true)) for (boolean isolated : List.of(false, true))
            for (boolean handshake : List.of(false, true)) for (ConnectStatus status : ConnectStatus.values())
                assertEquals(DROP, ClientConnectionRouting.route(false, remote, status, handshake, isolated));
    }
}
