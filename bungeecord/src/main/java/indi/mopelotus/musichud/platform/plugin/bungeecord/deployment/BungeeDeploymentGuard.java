package indi.mopelotus.musichud.platform.plugin.bungeecord.deployment;

import indi.mopelotus.musichud.network.ProxyDeploymentProbe;
import indi.mopelotus.musichud.platform.plugin.bungeecord.event.BungeeEventService;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeeNetworkManager;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayerProxy;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import java.util.function.BiConsumer;

public final class BungeeDeploymentGuard {
    private BungeeDeploymentGuard() {}

    public static boolean rejectBackendAuthority(ProxyServer proxy, BungeeNetworkManager network,
            ProxiedPlayer player, Server backend, String channel, byte[] bytes,
            BiConsumer<String, String> report) {
        if (!network.isOpen()) return false;
        boolean presence = ProxyDeploymentProbe.CHANNEL.equals(channel) && ProxyDeploymentProbe.isPresent(bytes);
        if (!presence && !network.isServerChannel(channel)) return false;
        if (proxy.getPlayer(player.getUniqueId()) != player || player.getServer() != backend
                || !BungeePlayerProxy.of(player).isConnected()) return false;
        if (!BungeePlayerProxy.disconnectOnce(player)) return false;
        try {
            ServerPlayerRegistry.getInstance().disconnect(BungeePlayerProxy.of(player));
            report.accept(backend.getInfo().getName(), channel);
            BungeeEventService.getInstance().firePlayerQuit(player);
        } finally {
            player.disconnect(new TextComponent("MusicHud TuneWeave: proxy/backend double installation detected. "
                    + "Keep the plugin only on the proxy and remove it from ALL backend servers, then reconnect."));
        }
        return true;
    }
}
