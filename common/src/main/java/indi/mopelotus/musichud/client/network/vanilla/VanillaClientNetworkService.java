package indi.mopelotus.musichud.client.network.vanilla;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import net.minecraft.client.Minecraft;

public interface VanillaClientNetworkService extends IClientNetworkService {
    void sendToNetworkServer(C2SPayload payload);

    @Override
    default <T extends C2SPayload> void sendToServer(T payload) {
        Minecraft minecraft = Minecraft.getInstance();
        var route = indi.mopelotus.musichud.client.services.ClientConnectionRouting.route(
                minecraft.player != null, minecraft.getCurrentServer() != null, MusicHud.getConnectStatus(),
                payload instanceof ConnectRequest, ClientConfig.getInstance().getEnableIsolatedMode());
        if (route == indi.mopelotus.musichud.client.services.ClientConnectionRouting.Route.REMOTE) {
            indi.mopelotus.musichud.network.PayloadFragments.sendC2S(payload, this::sendToNetworkServer);
        } else if (route == indi.mopelotus.musichud.client.services.ClientConnectionRouting.Route.LOCAL) {
            //noinspection unchecked
            NetworkReceiver<T> receiver = (NetworkReceiver<T>) IVanillaNetworkRegister.getMetaDataOrNew(payload.getClass(), null).receiver();
            if (receiver != null) {
                receiver.receive(payload, VanillaPlayerProxy.ofPlayer(minecraft.player));
            }
        } else {
            MusicHud.LOGGER.warn("Dropped C2S payload {}: no active route for connection status {}",
                    payload.getClass().getName(), MusicHud.getConnectStatus());
        }

    }
}
