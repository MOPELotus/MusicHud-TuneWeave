package indi.mopelotus.musichud.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.platform.plugin.velocity.config.VelocityServerConfig;
import indi.mopelotus.musichud.platform.plugin.velocity.event.VelocityEventService;
import indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityNetworkManager;
import indi.mopelotus.musichud.platform.plugin.velocity.command.VelocityAdminCommand;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "musichud_tuneweave",
        name = "MusicHud TuneWeave",
        version = PluginVersion.VERSION,
        description = "TuneWeave public playback coordination for Velocity",
        authors = {"Etern", "MOPELotus"}
)
public final class VelocityInitializer {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private VelocityNetworkManager networkManager;
    private VelocityAdminCommand adminCommand;

    @Inject
    public VelocityInitializer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        MusicHud.setCurrentEnvironment(Environment.of(Environment.Side.SERVER, Environment.Platform.VELOCITY));
        MusicHud.setConfigDirectory(dataDirectory);
        VelocityServerConfig.getInstance().initialize(dataDirectory);
        // TuneWeave is client-owned in distributed mode; Velocity only forwards
        // public playback state and must not launch a provider API process.
        VelocityServerConfig.getInstance().setStartupBinaryApiServerWhenLaunch(false);
        adminCommand = new VelocityAdminCommand();
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("musichud")
                        .aliases("mt", "musichud-tuneweave", "tuneweave")
                        .build(), adminCommand);

        networkManager = VelocityNetworkManager.getInstance();
        networkManager.initialize(proxy);
        try {
            MusicHud.init();
            MusicHud.onConfigLoaded();
            logger.info("MusicHud Velocity initialized with shared server core at {}", dataDirectory);
        } catch (RuntimeException exception) {
            shutdown();
            throw exception;
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (networkManager == null) return;
        String channel = event.getIdentifier().getId();
        var route = indi.mopelotus.musichud.network.ProxyMessagePolicy.route(channel, event.getSource() instanceof Player);
        if (route == indi.mopelotus.musichud.network.ProxyMessagePolicy.Route.FORWARD) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (route == indi.mopelotus.musichud.network.ProxyMessagePolicy.Route.DROP_BACKEND
                && event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection backend) {
            indi.mopelotus.musichud.platform.plugin.velocity.deployment.VelocityDeploymentGuard.rejectBackendAuthority(
                    proxy, networkManager, backend, channel, event.getData(),
                    (server, payload) -> logger.error("MusicHud TuneWeave double installation detected on backend {} "
                            + "(channel {}). Disconnected the affected player and closed its playback membership. "
                            + "Remove MusicHud TuneWeave from ALL backend servers; keep it only on the proxy.", server, payload));
            return;
        }
        if (route != indi.mopelotus.musichud.network.ProxyMessagePolicy.Route.HANDLE_CLIENT
                || !(event.getSource() instanceof Player player) || !networkManager.handles(channel)) return;
        networkManager.handle(player, channel, event.getData());
    }

    @Subscribe public void onServerChanged(com.velocitypowered.api.event.player.ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (networkManager == null) return;
        networkManager.probeBackend(player);
        if (!indi.mopelotus.musichud.server.ServerPlayerRegistry.getInstance().contains(player.getUniqueId())) return;
        var service = indi.mopelotus.musichud.server.api.MusicPlayerServerService.getInstance();
        var client = indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityPlayerProxy.of(player);
        networkManager.sendToPlayer(client, new indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage(
                service.getCurrentPlaybackSession(), service.getNextIdleMusicDetail()));
        networkManager.sendToPlayer(client, new indi.mopelotus.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage(service.getMusicQueue()));
        service.sendUpdateAllIdlePlaySourcesMessageTo(java.util.List.of(client));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        VelocityEventService.getInstance().firePlayerQuit(event.getPlayer());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        shutdown();
    }

    private void shutdown() {
        if (networkManager != null) {
            networkManager.close();
            networkManager = null;
        }
        VelocityEventService.getInstance().fireProxyStopping();
    }

}
