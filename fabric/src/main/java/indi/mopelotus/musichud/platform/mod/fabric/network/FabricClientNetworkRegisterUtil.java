package indi.mopelotus.musichud.platform.mod.fabric.network;

import indi.mopelotus.musichud.client.network.vanilla.CustomPacketPayloadWrapper;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class FabricClientNetworkRegisterUtil {
    public static <T extends IPayload> void register(CustomPacketPayload.Type<CustomPacketPayloadWrapper<T>> type, NetworkReceiver<T> clientReceiver) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            var client = context.client();
            // The response sender belongs to the originating PLAY addon, even after a proxy switch.
            var currentSender = client.getConnection() == null ? null : ClientPlayNetworking.getSender();
            indi.mopelotus.musichud.client.services.ClientPayloadAdmission.receiveFromListener(
                    context.responseSender(), currentSender,
                    () -> client.player == null ? null : VanillaPlayerProxy.ofPlayer(client.player),
                    player -> indi.mopelotus.musichud.client.services.ConnectionManager.getInstance()
                            .captureClientPayload(true, player, payload.getPayload()),
                    player -> clientReceiver.receive(payload.getPayload(), player));
        });
    }
}
