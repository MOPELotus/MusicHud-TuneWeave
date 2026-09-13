package indi.mopelotus.musichud.bungeecord;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.platform.plugin.bungeecord.command.BungeeAdminCommand;
import indi.mopelotus.musichud.platform.plugin.bungeecord.config.BungeeServerConfig;
import indi.mopelotus.musichud.platform.plugin.bungeecord.event.BungeeEventService;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeeNetworkManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import java.util.List;
import java.util.logging.Logger;

public final class BungeeInitializer extends Plugin {
    private ProxyServer proxy;
    private Logger logger;
    private BungeeNetworkManager networkManager;
    private BungeeAdminCommand adminCommand;

    @Override public void onEnable() {
        proxy = getProxy();
        logger = getLogger();
        indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeeNativeTransport.verifyRuntime(proxy.getClass().getClassLoader());
        MusicHud.setCurrentEnvironment(Environment.of(Environment.Side.SERVER, Environment.Platform.BUNGEECORD));
        MusicHud.setConfigDirectory(getDataFolder().toPath());
        BungeeServerConfig.getInstance().initialize(getDataFolder().toPath());
        networkManager = BungeeNetworkManager.getInstance();
        networkManager.initialize(proxy);
        adminCommand = new BungeeAdminCommand();
        proxy.getPluginManager().registerCommand(this, adminCommand);
        proxy.getPluginManager().registerListener(this, new BungeeEventListener(proxy, logger, networkManager));
        try {
            MusicHud.init();
            MusicHud.onConfigLoaded();
            logger.info("MusicHud BungeeCord initialized with shared server core");
        } catch (RuntimeException exception) {
            onDisable();
            throw exception;
        }
    }

    @Override public void onDisable() {
        if (networkManager != null) {
            networkManager.close();
            networkManager = null;
        }
        if (proxy != null) {
            proxy.getPluginManager().unregisterListeners(this);
            if (adminCommand != null) proxy.getPluginManager().unregisterCommand(adminCommand);
        }
        adminCommand = null;
        BungeeEventService.getInstance().fireProxyStopping();
    }
}
