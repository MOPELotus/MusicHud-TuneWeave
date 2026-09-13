package indi.mopelotus.musichud.platform.plugin.bungeecord.network;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import indi.mopelotus.musichud.network.IPlayerClient;

import java.util.Objects;
import java.util.UUID;

public final class BungeePlayerProxy implements IPlayerClient {
    private static final com.google.common.cache.Cache<ProxiedPlayer, Boolean> DISCONNECTED = com.google.common.cache.CacheBuilder.newBuilder().weakKeys().build();
    private final ProxiedPlayer player;

    private BungeePlayerProxy(ProxiedPlayer player) {
        this.player = player;
    }

    public static BungeePlayerProxy of(ProxiedPlayer player) {
        return new BungeePlayerProxy(player);
    }

    @Override
    public UUID getUUID() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public ClientType getClientType() {
        return ClientType.REMOTE;
    }

    public static void disconnect(ProxiedPlayer player) { disconnectOnce(player); }
    public static boolean disconnectOnce(ProxiedPlayer player) {
        return DISCONNECTED.asMap().putIfAbsent(player, true) == null;
    }
    @Override public boolean isConnected() { return DISCONNECTED.getIfPresent(player) == null && BungeeNetworkManager.getInstance().isCurrentConnection(player); }
    @Override public Object connectionIdentity() { return player; }

    @Override
    public boolean equals(Object object) {
        return object instanceof BungeePlayerProxy other && getUUID().equals(other.getUUID());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUUID());
    }
}
