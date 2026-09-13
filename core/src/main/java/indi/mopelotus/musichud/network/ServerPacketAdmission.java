package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;

/** Arrival-time membership identity, checked again before queued work executes. */
public final class ServerPacketAdmission {
    private final IPlayerClient player;
    private final boolean handshake;
    private final Object session;
    private ServerPacketAdmission(IPayload payload, IPlayerClient player) {
        this.player = player;
        handshake = payload instanceof ConnectRequest;
        session = ServerPlayerRegistry.getInstance().sessionToken(player.getUUID());
    }
    public static ServerPacketAdmission capture(IPayload payload, IPlayerClient player) {
        return new ServerPacketAdmission(payload, player);
    }
    public boolean allowed() {
        return player.isConnected() && (handshake || session != null
                && ServerPlayerRegistry.getInstance().sessionToken(player.getUUID()) == session);
    }
}
