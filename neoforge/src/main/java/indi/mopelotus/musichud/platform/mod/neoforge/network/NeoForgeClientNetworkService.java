package indi.mopelotus.musichud.platform.mod.neoforge.network;

import indi.mopelotus.musichud.client.network.vanilla.CustomPacketPayloadWrapper;
import indi.mopelotus.musichud.client.network.vanilla.VanillaClientNetworkService;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.minecraft.client.Minecraft;
import java.util.Objects;

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
    public void sendToNetworkServer(C2SPayload payload) {
        // NeoForge moved its distributor helper within 1.21.6–1.21.8; the listener API is stable.
        Objects.requireNonNull(Minecraft.getInstance().getConnection())
                .send(new CustomPacketPayloadWrapper<>(payload));
    }
}
