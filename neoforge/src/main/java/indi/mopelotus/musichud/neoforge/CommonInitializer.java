package indi.mopelotus.musichud.neoforge;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.platform.mod.config.ClientConfigDefinition;
import indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition;
import indi.mopelotus.musichud.platform.mod.neoforge.event.NeoForgeClientEventService;
import indi.mopelotus.musichud.platform.mod.neoforge.event.NeoForgeCommonEventService;
import indi.mopelotus.musichud.platform.mod.neoforge.network.NeoForgeNetworkManager;
import indi.mopelotus.musichud.platform.mod.neoforge.registry.NeoForgeKeyRegistryService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.fml.ModList;

@Mod(MusicHud.MOD_ID)
public final class CommonInitializer {
    private final IEventBus modEventBus;

    @SuppressWarnings("unused")
    public CommonInitializer(IEventBus eventBus, ModContainer container) {
        if (ModList.get().isLoaded("music_hud")) {
            throw new IllegalStateException("MusicHud TuneWeave cannot run with upstream MusicHud (music_hud). Remove upstream MusicHud.");
        }
        modEventBus = eventBus;
        modEventBus.register(this);

        boolean inClient = FMLEnvironment.dist.isClient();
        MusicHud.setCurrentEnvironment(Environment.of(
                inClient ? Environment.Side.CLIENT : Environment.Side.SERVER,
                Environment.Platform.NEOFORGE
        ));
        MusicHud.setConfigDirectory(FMLPaths.CONFIGDIR.get());
        ServerConfigDefinition.getInstance().load();
        if (inClient) {
            ClientConfigDefinition.getInstance().load();
            container.registerExtensionPoint(IConfigScreenFactory.class, new ConfigScreenFactory());
        }
        MusicHud.init();
        NeoForgeCommonEventService.getInstance();
        if (inClient) {
            NeoForgeClientEventService.getInstance();
            modEventBus.register(NeoForgeKeyRegistryService.getInstance());
        }
        MusicHud.onConfigLoaded();
    }

    @SubscribeEvent
    private void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent event) {
        NeoForgeNetworkManager.getInstance().onRegisterPayloadHandlers(event);
    }

}

