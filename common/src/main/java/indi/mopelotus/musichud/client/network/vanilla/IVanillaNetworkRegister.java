package indi.mopelotus.musichud.client.network.vanilla;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.IPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface IVanillaNetworkRegister extends INetworkRegister {
    record PayloadMetadata<T extends IPayload>(CustomPacketPayload.Type<CustomPacketPayloadWrapper<T>> type, NetworkReceiver<?> receiver){}
    Map<Class<? extends IPayload>, PayloadMetadata<?>> metadataMap = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T extends IPayload> PayloadMetadata<T> getMetaDataOrNew(Class<T> customPacketPayloadClass, @Nullable NetworkReceiver<T> networkReceiver) {
        return (PayloadMetadata<T>) metadataMap.computeIfAbsent(customPacketPayloadClass, clazz -> {
            if (networkReceiver == null) {
                throw new IllegalStateException("No pre-cached metadata, and networkReceiver is null");
            }
            return new PayloadMetadata<T>(new CustomPacketPayload.Type<>(Identifier.parse(indi.mopelotus.musichud.network.ProtocolChannels.id(clazz))), networkReceiver);
        });
    }
}
