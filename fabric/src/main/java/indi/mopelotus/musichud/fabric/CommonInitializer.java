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
        }
        MusicHud.init();
        MusicHud.onConfigLoaded();
    }
}
