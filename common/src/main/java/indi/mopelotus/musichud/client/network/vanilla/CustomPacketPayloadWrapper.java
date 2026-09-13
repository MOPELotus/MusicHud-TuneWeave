package indi.mopelotus.musichud.client.network.vanilla;

import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.IPayload;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

@AllArgsConstructor
public class CustomPacketPayloadWrapper<T extends IPayload> implements CustomPacketPayload {
    @Getter
    T payload;

    /** Deliver the domain payload, never the Minecraft transport envelope. */
    public void receive(NetworkReceiver<T> receiver, IPlayerClient player) {
        receiver.receive(payload, player);
    }

    @Override
    @NonNull
    public Type<? extends CustomPacketPayloadWrapper<? extends IPayload>> type() {
        return IVanillaNetworkRegister.getMetaDataOrNew(payload.getClass(), null).type();
    }
}
