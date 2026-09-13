package indi.mopelotus.musichud.platform.plugin.velocity.deployment;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import indi.mopelotus.musichud.platform.plugin.velocity.event.VelocityEventService;
import indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityNetworkManager;
import indi.mopelotus.musichud.platform.plugin.velocity.network.VelocityPlayerProxy;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import net.kyori.adventure.text.Component;

import java.util.function.BiConsumer;

/** A backend must never send this plugin's server authority through a proxy installation. */
public final class VelocityDeploymentGuard {
    private VelocityDeploymentGuard() {}

    public static boolean rejectBackendAuthority(ProxyServer proxy, VelocityNetworkManager network,
                                                  ServerConnection backend, String channel, byte[] data,
                                                  BiConsumer<String, String> report) {
        if (!network.isOpen()) return false;
        boolean presence = indi.mopelotus.musichud.network.ProxyDeploymentProbe.CHANNEL.equals(channel)
                && indi.mopelotus.musichud.network.ProxyDeploymentProbe.isPresent(data);
        if (!presence && !network.isServerChannel(channel)) return false;
        Player player = backend.getPlayer();
        if (proxy.getPlayer(player.getUniqueId()).orElse(null) != player
                || player.getCurrentServer().orElse(null) != backend
                || !VelocityPlayerProxy.of(player).isConnected()) return false;

        // Permanently close this physical peer before asynchronous work can join it again.
        // A fresh login has a new Player identity and is admitted normally.
        if (!VelocityPlayerProxy.disconnectOnce(player)) return false;
        try {
            ServerPlayerRegistry.getInstance().disconnect(VelocityPlayerProxy.of(player));
            report.accept(backend.getServerInfo().getName(), channel);
            VelocityEventService.getInstance().firePlayerQuit(player);
        } finally {
            player.disconnect(Component.text("MusicHud TuneWeave: proxy/backend double installation detected. "
                    + "Keep the plugin only on the proxy and remove it from ALL backend servers, then reconnect."));
        }
        return true;
    }
}
