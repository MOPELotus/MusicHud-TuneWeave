package indi.mopelotus.musichud.platform.mod.neoforge.network;

import indi.mopelotus.musichud.client.network.vanilla.CustomPacketPayloadWrapper;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.client.network.vanilla.VanillaClientNetworkService;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

@SuppressWarnings("unused")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NeoForgeClientNetworkService implements VanillaClientNetworkService {
    private static volatile NeoForgeClientNetworkService instance;

    public static NeoForgeClientNetworkService getInstance() {
        if (instance == null) {
            synchronized (NeoForgeClientNetworkService.class) {
                if (instance == null) {
                    instance = new NeoForgeClientNetworkService();
                }
            }
        }
        return instance;
    }

    @Override
    public boolean isNetworkChannelReady(C2SPayload payload) {
        var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        return listener != null && net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
                listener, new CustomPacketPayloadWrapper<>(payload).type().id());
    }

    @Override
    public void sendToNetworkServer(C2SPayload payload) {
        ClientPacketDistributor.sendToServer(new CustomPacketPayloadWrapper<>(payload));
    }
    static <T extends indi.mopelotus.musichud.network.payloads.IPayload> void receive(
            CustomPacketPayloadWrapper<T> payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context,
            indi.mopelotus.musichud.network.NetworkReceiver<T> receiver) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        indi.mopelotus.musichud.client.services.ClientPayloadAdmission.receiveFromListener(
                context.listener(), minecraft.getConnection(), () -> minecraft.player,
                player -> indi.mopelotus.musichud.client.services.ConnectionManager.getInstance()
                        .captureClientPayload(true, VanillaPlayerProxy.ofPlayer(player), payload.getPayload()),
                player -> payload.receive(receiver, VanillaPlayerProxy.ofPlayer(player)));
    }

}
