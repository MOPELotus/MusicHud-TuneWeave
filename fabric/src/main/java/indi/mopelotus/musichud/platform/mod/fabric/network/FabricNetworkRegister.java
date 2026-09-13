package indi.mopelotus.musichud.platform.mod.fabric.network;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.network.vanilla.CustomPacketPayloadWrapper;
import indi.mopelotus.musichud.client.network.vanilla.IVanillaNetworkRegister;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.client.network.vanilla.StreamCodecWrapper;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.platform.Environment;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

@SuppressWarnings("unused")
public class FabricNetworkRegister implements INetworkRegister {
    private static volatile FabricNetworkRegister instance;

    public static FabricNetworkRegister getInstance() {
        if (instance == null) {
            synchronized (FabricNetworkRegister.class) {
                if (instance == null) {
                    instance = new FabricNetworkRegister();
                }
            }
        }
        return instance;
    }

    @Override
    public <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    ) {
        indi.mopelotus.musichud.network.PayloadFragments.register(clazz, codec, serverReceiver, true);
        CustomPacketPayload.Type<CustomPacketPayloadWrapper<T>> type = IVanillaNetworkRegister.getMetaDataOrNew(clazz, serverReceiver).type();
        PayloadTypeRegistry.serverboundPlay().register(type, StreamCodecWrapper.of(codec));

        Environment.Side side = MusicHud.getCurrentEnvironment().getSide();

        ServerPlayNetworking.registerGlobalReceiver(type, (receive, context) -> {
            serverReceiver.receive(receive.getPayload(), VanillaPlayerProxy.ofPlayer(context.player()));
        });
    }

    @Override
    public <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        indi.mopelotus.musichud.network.PayloadFragments.register(clazz, codec, clientReceiver, false);
        CustomPacketPayload.Type<CustomPacketPayloadWrapper<T>> type = IVanillaNetworkRegister.getMetaDataOrNew(clazz, clientReceiver).type();
        PayloadTypeRegistry.clientboundPlay().register(type, StreamCodecWrapper.of(codec));

        Environment.Side side = MusicHud.getCurrentEnvironment().getSide();
        if (side == Environment.Side.CLIENT) {
            FabricClientNetworkRegisterUtil.register(type, clientReceiver);
        }
    }

    @Override
    public <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientOrServerReceiver
    ) {
        if (S2CPayload.class.isAssignableFrom(clazz)) {
            registerS2CPayload(clazz, codec, clientOrServerReceiver);
        } else if (C2SPayload.class.isAssignableFrom(clazz)) {
            registerC2SPayload(clazz, codec, clientOrServerReceiver);
        } else {
            throw new IllegalArgumentException("Payload class must implements S2CPayload or C2SPayload");
        }
    }
}
