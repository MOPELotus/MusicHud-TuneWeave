package indi.mopelotus.musichud.fabric;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.platform.mod.config.ClientConfigDefinition;
import indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class CommonInitializer implements ModInitializer {

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("music_hud")) {
            throw new IllegalStateException("MusicHud TuneWeave cannot run with upstream MusicHud (music_hud). Remove upstream MusicHud.");
        }
        boolean inClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        MusicHud.setCurrentEnvironment(Environment.of(inClient ? Environment.Side.CLIENT : Environment.Side.SERVER, Environment.Platform.FABRIC));
        MusicHud.setConfigDirectory(FabricLoader.getInstance().getConfigDir());
        ServerConfigDefinition.getInstance().load();
        if (inClient) {
            ClientConfigDefinition.getInstance().load();
            var mod = FabricLoader.getInstance().getModContainer(MusicHud.MOD_ID).orElseThrow();
            indi.mopelotus.musichud.client.update.ClientUpdateService.initialize(mod.getOrigin().getPaths().getFirst(),
                    mod.getMetadata().getVersion().getFriendlyString(), "fabric",
                    FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
        }
        if (!inClient) {
            var type = indi.mopelotus.musichud.platform.mod.network.DeploymentProbePayload.TYPE;
            var codec = indi.mopelotus.musichud.platform.mod.network.DeploymentProbePayload.CODEC;
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(type, codec);
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(type, codec);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type,
                    (payload, context) -> indi.mopelotus.musichud.platform.mod.network.DedicatedDeploymentProbe.receive(payload, context.player()));
        }
        MusicHud.init();
        MusicHud.onConfigLoaded();
        if (inClient) indi.mopelotus.musichud.client.update.ClientUpdateService.watchSuccessfulStartup();
    }
}
