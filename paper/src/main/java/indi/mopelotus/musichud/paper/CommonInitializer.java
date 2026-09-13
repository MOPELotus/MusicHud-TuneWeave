package indi.mopelotus.musichud.paper;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.platform.plugin.paper.config.ServerConfigDefinition;
import indi.mopelotus.musichud.platform.plugin.paper.event.PaperEventService;
import indi.mopelotus.musichud.platform.plugin.paper.network.PaperNetworkManager;
import org.bukkit.plugin.java.JavaPlugin;
import indi.mopelotus.musichud.platform.plugin.paper.command.PaperServerAdminCommand;

@SuppressWarnings("unused")
public final class CommonInitializer extends JavaPlugin {
    private PaperEventService eventService;
    private PaperNetworkManager networkManager;

    @Override
    public void onEnable() {
        var deployment = indi.mopelotus.musichud.platform.plugin.paper.deployment.PaperDeploymentGuard.inspect(
                getServer(), name -> Class.forName(name, true, getServer().getClass().getClassLoader()));
        if (!indi.mopelotus.musichud.platform.plugin.paper.deployment.PaperDeploymentGuard.allow(
                deployment, getLogger()::severe, () -> getServer().getPluginManager().disablePlugin(this))) return;

        MusicHud.setCurrentEnvironment(Environment.of(Environment.Side.SERVER, Environment.Platform.PAPER));
        MusicHud.setConfigDirectory(getDataFolder().toPath());

        saveDefaultConfig();

        eventService = PaperEventService.getInstance();
        eventService.initialize(this);
        new PaperServerAdminCommand().register(this);
        networkManager = PaperNetworkManager.getInstance();
        networkManager.initialize(this);

        ServerConfigDefinition serverConfig = ServerConfigDefinition.getInstance();
        serverConfig.initialize(this);
        // TuneWeave is client-owned in distributed mode; the plugin only coordinates
        // public sessions and must never launch an embedded provider API server.
        serverConfig.setStartupBinaryApiServerWhenLaunch(false);

        try {
            MusicHud.init();
            MusicHud.onConfigLoaded();
        } catch (RuntimeException e) {
            shutdownServices();
            throw e;
        }
    }

    @Override
    public void onDisable() {
        shutdownServices();
    }

    private void shutdownServices() {
        if (networkManager != null) {
            networkManager.close();
            networkManager = null;
        }
        if (eventService != null) {
            eventService.fireServerStopping();
        }
        eventService = null;
    }
}
