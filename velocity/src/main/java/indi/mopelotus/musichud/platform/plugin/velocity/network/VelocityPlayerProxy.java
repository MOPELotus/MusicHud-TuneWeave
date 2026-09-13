package indi.mopelotus.musichud.platform.plugin.velocity.network;

import com.velocitypowered.api.proxy.Player;
import indi.mopelotus.musichud.network.IPlayerClient;

import java.util.Objects;
import java.util.UUID;

public final class VelocityPlayerProxy implements IPlayerClient {
    private static final com.google.common.cache.Cache<Player, Boolean> DISCONNECTED = com.google.common.cache.CacheBuilder.newBuilder().weakKeys().build();
    private final Player player;

    private VelocityPlayerProxy(Player player) {
        this.player = player;
    }

    public static VelocityPlayerProxy of(Player player) {
        return new VelocityPlayerProxy(player);
    }

    @Override
    public UUID getUUID() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getUsername();
    }

    @Override
    public ClientType getClientType() {
        return ClientType.REMOTE;
    }

    public static void disconnect(Player player) { disconnectOnce(player); }
    public static boolean disconnectOnce(Player player) {
        return DISCONNECTED.asMap().putIfAbsent(player, true) == null;
    }
    @Override public boolean isConnected() { return DISCONNECTED.getIfPresent(player) == null && VelocityNetworkManager.getInstance().isCurrentConnection(player); }
    @Override public Object connectionIdentity() { return player; }

    @Override
    public boolean equals(Object object) {
        return object instanceof VelocityPlayerProxy other && getUUID().equals(other.getUUID());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUUID());
    }
}
