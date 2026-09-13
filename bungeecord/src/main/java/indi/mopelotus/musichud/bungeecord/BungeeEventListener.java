package indi.mopelotus.musichud.bungeecord;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.ProxyMessagePolicy;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage;
import indi.mopelotus.musichud.platform.plugin.bungeecord.deployment.BungeeDeploymentGuard;
import indi.mopelotus.musichud.platform.plugin.bungeecord.event.BungeeEventService;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeeNetworkManager;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayerProxy;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import java.util.List;
import java.util.logging.Logger;

public final class BungeeEventListener implements Listener {
    private final ProxyServer proxy;
    private final Logger logger;
    private final BungeeNetworkManager networkManager;

    public BungeeEventListener(ProxyServer proxy, Logger logger, BungeeNetworkManager networkManager) {
        this.proxy = proxy;
        this.logger = logger;
        this.networkManager = networkManager;
    }

    @EventHandler public void onPluginMessage(PluginMessageEvent event) {
        if (!networkManager.isOpen()) return;
        String channel = event.getTag();
        var route = ProxyMessagePolicy.route(channel, event.getSender() instanceof ProxiedPlayer);
        if (route == ProxyMessagePolicy.Route.FORWARD) return;
        event.setCancelled(true);
        if (route == ProxyMessagePolicy.Route.DROP_BACKEND && event.getSender() instanceof Server backend
                && event.getReceiver() instanceof ProxiedPlayer player) {
            BungeeDeploymentGuard.rejectBackendAuthority(proxy, networkManager, player, backend, channel, event.getData(),
                    (server, payload) -> logger.severe("MusicHud TuneWeave double installation detected on backend "
                            + server + " (channel " + payload + "). Disconnected the affected player and closed its playback membership. "
                            + "Remove MusicHud TuneWeave from ALL backend servers; keep it only on the proxy."));
            return;
        }
        if (route == ProxyMessagePolicy.Route.HANDLE_CLIENT && event.getSender() instanceof ProxiedPlayer player
                && networkManager.handles(channel)) networkManager.handle(player, channel, event.getData());
    }

    @EventHandler public void onServerChanged(ServerSwitchEvent event) {
        if (!networkManager.isOpen()) return;
        ProxiedPlayer player = event.getPlayer();
        networkManager.probeBackend(player);
        networkManager.afterBackendReady(player, 0, () -> {
        if (!ServerPlayerRegistry.getInstance().contains(player.getUniqueId())) return;
        var service = MusicPlayerServerService.getInstance();
        var client = BungeePlayerProxy.of(player);
        networkManager.sendToPlayer(client, new SyncCurrentPlayingMessage(
                service.getCurrentPlaybackSession(), service.getNextIdleMusicDetail()));
        networkManager.sendToPlayer(client, new RefreshMusicQueueMessage(service.getMusicQueue()));
        service.sendUpdateAllIdlePlaySourcesMessageTo(List.of(client));
        });
    }

    @EventHandler public void onDisconnect(PlayerDisconnectEvent event) {
        BungeeEventService.getInstance().firePlayerQuit(event.getPlayer());
    }

}
