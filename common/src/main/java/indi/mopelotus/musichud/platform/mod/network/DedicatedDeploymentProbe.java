package indi.mopelotus.musichud.platform.mod.network;

import indi.mopelotus.musichud.network.BackendDeploymentResponder;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/** No client classes: a proxy consumes the reply before it reaches the client. */
public final class DedicatedDeploymentProbe {
    private DedicatedDeploymentProbe() {}

    public static void receive(DeploymentProbePayload payload, ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        var connection = player.connection;
        BackendDeploymentResponder.respond(server.isDedicatedServer(), payload.request(),
                () -> server.isRunning() && connection.isAcceptingMessages()
                        && player.connection == connection
                        && server.getPlayerList().getPlayer(player.getUUID()) == player,
                server::execute,
                () -> connection.send(new ClientboundCustomPayloadPacket(DeploymentProbePayload.presence())));
    }
}
