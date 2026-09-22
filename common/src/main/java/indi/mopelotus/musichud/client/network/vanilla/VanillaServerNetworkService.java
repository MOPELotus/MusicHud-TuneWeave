package indi.mopelotus.musichud.client.network.vanilla;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

public interface VanillaServerNetworkService extends IServerNetworkService {
    void sendToNetworkPlayer(IPlayerClient player, S2CPayload payload);

    @Override
    default <T extends S2CPayload> void sendToPlayer(IPlayerClient player, T payload) {
        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT && player.getClientType() == IPlayerClient.ClientType.LOCAL) {
            //noinspection unchecked
            NetworkReceiver<T> receiver = (NetworkReceiver<T>) IVanillaNetworkRegister
                    .getMetaDataOrNew(payload.getClass(), null).receiver();
            if (receiver != null) {
                var admission = indi.mopelotus.musichud.client.services.ConnectionManager.getInstance()
                        .captureClientPayload(false, player, payload);
                // One FIFO client queue preserves response/push order without taking the
                // connection lock while a server sender still holds its state lock.
                net.minecraft.client.Minecraft.getInstance().schedule(() -> indi.mopelotus.musichud.network.ClientPacketContext.receive(
                        admission, () -> receiver.receive(payload, player)));
            } else {
                throw new IllegalStateException();
            }
        } else if (player.getClientType() == IPlayerClient.ClientType.REMOTE) {
            indi.mopelotus.musichud.network.PayloadFragments.sendS2C(payload, part -> sendToNetworkPlayer(player, part));
        } else {
            throw new IllegalStateException();
        }
    }
}
