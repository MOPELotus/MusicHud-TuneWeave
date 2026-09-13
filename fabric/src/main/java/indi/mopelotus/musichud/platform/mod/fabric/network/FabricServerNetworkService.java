package indi.mopelotus.musichud.platform.mod.fabric.network;

import indi.mopelotus.musichud.client.network.vanilla.CustomPacketPayloadWrapper;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.client.network.vanilla.VanillaServerNetworkService;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.payloads.IPayload;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class FabricServerNetworkService implements VanillaServerNetworkService {
    private static volatile FabricServerNetworkService instance;
    private final Map<Class<? extends IPayload>, CustomPacketPayload.Type<?>> typeMap = new ConcurrentHashMap<>();

    public static FabricServerNetworkService getInstance() {
        if (instance == null) {
            synchronized (FabricServerNetworkService.class) {
                if (instance == null) {
                    instance = new FabricServerNetworkService();
                }
            }
        }
        return instance;
    }

    @Override
    public void sendToNetworkPlayer(IPlayerClient playerClient, S2CPayload payload) {
        if (playerClient instanceof VanillaPlayerProxy player && player.getPlayer() instanceof ServerPlayer serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new CustomPacketPayloadWrapper<>(payload));
        } else {
            MusicHud.LOGGER.warn("Dropped S2C payload {}: unsupported player target {} ({})",
                    payload.getClass().getName(), playerClient.getUUID(), playerClient.getClass().getName());
        }
    }
}
