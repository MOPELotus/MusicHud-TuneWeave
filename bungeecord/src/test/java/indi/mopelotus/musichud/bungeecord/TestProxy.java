package indi.mopelotus.musichud.bungeecord;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
/** Test double for the pinned abstract API; unused operations fail immediately. */
final class TestProxy extends ProxyServer {
    static indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayGate.Transport ready() {
        return new indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayGate.Transport() {
            public boolean ready() { return true; }
            public void execute(Runnable task) { task.run(); }
            public void later(Runnable task) { throw new AssertionError("already ready"); }
        };
    }
    static final net.md_5.bungee.api.connection.Server BACKEND = (net.md_5.bungee.api.connection.Server)
            java.lang.reflect.Proxy.newProxyInstance(TestProxy.class.getClassLoader(),
                    new Class<?>[]{net.md_5.bungee.api.connection.Server.class},
                    (p, m, a) -> { if (m.getName().equals("isConnected")) return true; throw new AssertionError(m.getName()); });
    final AtomicReference<ProxiedPlayer> active;
    final Set<String> channels = new HashSet<>();
    TestProxy(AtomicReference<ProxiedPlayer> active) { this.active = active; }
    @Override public java.lang.String getName() { throw new UnsupportedOperationException("getName"); }
    @Override public java.lang.String getVersion() { throw new UnsupportedOperationException("getVersion"); }
    @Override public java.lang.String getTranslation(java.lang.String a0, java.lang.Object... a1) { throw new UnsupportedOperationException("getTranslation"); }
    @Override public java.util.logging.Logger getLogger() { throw new UnsupportedOperationException("getLogger"); }
    @Override public java.util.Collection<net.md_5.bungee.api.connection.ProxiedPlayer> getPlayers() { throw new UnsupportedOperationException("getPlayers"); }
    @Override public net.md_5.bungee.api.connection.ProxiedPlayer getPlayer(java.lang.String a0) { return active.get(); }
    @Override public net.md_5.bungee.api.connection.ProxiedPlayer getPlayer(java.util.UUID a0) { return active.get(); }
    @Override public java.util.Map<java.lang.String, net.md_5.bungee.api.config.ServerInfo> getServers() { throw new UnsupportedOperationException("getServers"); }
    @Override public net.md_5.bungee.api.config.ServerInfo getServerInfo(java.lang.String a0) { throw new UnsupportedOperationException("getServerInfo"); }
    @Override public net.md_5.bungee.api.plugin.PluginManager getPluginManager() { throw new UnsupportedOperationException("getPluginManager"); }
    @Override public net.md_5.bungee.api.config.ConfigurationAdapter getConfigurationAdapter() { throw new UnsupportedOperationException("getConfigurationAdapter"); }
    @Override public void setConfigurationAdapter(net.md_5.bungee.api.config.ConfigurationAdapter a0) { throw new UnsupportedOperationException("setConfigurationAdapter"); }
    @Override public net.md_5.bungee.api.ReconnectHandler getReconnectHandler() { throw new UnsupportedOperationException("getReconnectHandler"); }
    @Override public void setReconnectHandler(net.md_5.bungee.api.ReconnectHandler a0) { throw new UnsupportedOperationException("setReconnectHandler"); }
    @Override public void stop() { throw new UnsupportedOperationException("stop"); }
    @Override public void stop(java.lang.String a0) { throw new UnsupportedOperationException("stop"); }
    @Override public void registerChannel(java.lang.String a0) { channels.add(a0); }
    @Override public void unregisterChannel(java.lang.String a0) { channels.remove(a0); }
    @Override public java.util.Collection<java.lang.String> getChannels() { return channels; }
    @Override public java.lang.String getGameVersion() { throw new UnsupportedOperationException("getGameVersion"); }
    @Override public int getProtocolVersion() { throw new UnsupportedOperationException("getProtocolVersion"); }
    @Override public net.md_5.bungee.api.config.ServerInfo constructServerInfo(java.lang.String a0, java.net.InetSocketAddress a1, java.lang.String a2, boolean a3) { throw new UnsupportedOperationException("constructServerInfo"); }
    @Override public net.md_5.bungee.api.config.ServerInfo constructServerInfo(java.lang.String a0, java.net.SocketAddress a1, java.lang.String a2, boolean a3) { throw new UnsupportedOperationException("constructServerInfo"); }
    @Override public net.md_5.bungee.api.CommandSender getConsole() { throw new UnsupportedOperationException("getConsole"); }
    @Override public java.io.File getPluginsFolder() { throw new UnsupportedOperationException("getPluginsFolder"); }
    @Override public net.md_5.bungee.api.scheduler.TaskScheduler getScheduler() { throw new UnsupportedOperationException("getScheduler"); }
    @Override public int getOnlineCount() { throw new UnsupportedOperationException("getOnlineCount"); }
    @Override public void broadcast(java.lang.String a0) { throw new UnsupportedOperationException("broadcast"); }
    @Override public void broadcast(net.md_5.bungee.api.chat.BaseComponent... a0) { throw new UnsupportedOperationException("broadcast"); }
    @Override public void broadcast(net.md_5.bungee.api.chat.BaseComponent a0) { throw new UnsupportedOperationException("broadcast"); }
    @Override public java.util.Collection<java.lang.String> getDisabledCommands() { throw new UnsupportedOperationException("getDisabledCommands"); }
    @Override public net.md_5.bungee.api.ProxyConfig getConfig() { throw new UnsupportedOperationException("getConfig"); }
    @Override public java.util.Collection<net.md_5.bungee.api.connection.ProxiedPlayer> matchPlayer(java.lang.String a0) { throw new UnsupportedOperationException("matchPlayer"); }
    @Override public net.md_5.bungee.api.Title createTitle() { throw new UnsupportedOperationException("createTitle"); }
    @Override public net.md_5.bungee.api.ProxyServer.Unsafe unsafe() { throw new UnsupportedOperationException("unsafe"); }
}
