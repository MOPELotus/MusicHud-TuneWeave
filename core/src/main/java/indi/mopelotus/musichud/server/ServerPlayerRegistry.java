package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.interfaces.ICommonEventService;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.interfaces.ServerRegister;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.IPlayerClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/** Provider-neutral registry of clients connected to the MusicHud TuneWeave protocol. */
public final class ServerPlayerRegistry {
    private static final ServerPlayerRegistry INSTANCE = new ServerPlayerRegistry();

    private final ConcurrentHashMap<UUID, IPlayerClient> players = new ConcurrentHashMap<>();
    // Weak keys use identity, keeping a physical quit closed while old workers still retain that peer.
    private final com.google.common.cache.Cache<Object, Boolean> disconnected =
            com.google.common.cache.CacheBuilder.newBuilder().weakKeys().build();
    private final CopyOnWriteArraySet<Consumer<Collection<IPlayerClient>>> listeners =
            new CopyOnWriteArraySet<>();

    private ServerPlayerRegistry() {
    }

    public static ServerPlayerRegistry getInstance() {
        return INSTANCE;
    }

    public void join(IPlayerClient player) {
        var previous = new java.util.concurrent.atomic.AtomicReference<IPlayerClient>();
        var joined = new java.util.concurrent.atomic.AtomicBoolean();
        players.compute(player.getUUID(), (id, current) -> {
            // The check and write share leave's per-UUID map lock. An old offline join cannot replace a new peer.
            if (!canJoin(player)) return current;
            previous.set(current);
            joined.set(true);
            return player;
        });
        if (!joined.get()) return;
        if (previous.get() != null) indi.mopelotus.musichud.network.PayloadFragments.forgetServerPeer(previous.get());
        if (!canJoin(player)) {
            // equals() may compare UUID only. Remove precisely our write, never a newer wrapper/connection.
            players.computeIfPresent(player.getUUID(), (id, current) -> current == player ? null : current);
            indi.mopelotus.musichud.network.PayloadFragments.forgetServerPeer(player);
            notifyListeners();
            return;
        }
        MusicHud.LOGGER.info("TuneWeave protocol player joined: {} ({}) registrySize={}",
                player.getUUID(), player.getClientType(), players.size());
        notifyListeners();
    }

    private boolean canJoin(IPlayerClient player) {
        return disconnected.getIfPresent(player.connectionIdentity()) == null && player.isConnected();
    }

    /** Physical quit; a user-requested protocol disconnect must keep using leave so it can reconnect. */
    public void disconnect(IPlayerClient player) {
        disconnected.put(player.connectionIdentity(), Boolean.TRUE);
        leave(player);
    }

    public void leave(IPlayerClient player) {
        java.util.concurrent.atomic.AtomicReference<IPlayerClient> removed = new java.util.concurrent.atomic.AtomicReference<>();
        players.computeIfPresent(player.getUUID(), (id, current) -> {
            if (current.connectionIdentity() != player.connectionIdentity()) return current;
            removed.set(current); return null;
        });
        if (removed.get() != null) {
            indi.mopelotus.musichud.network.PayloadFragments.forgetServerPeer(removed.get());
            MusicHud.LOGGER.info("TuneWeave protocol player left: {} registrySize={}",
                    player.getUUID(), players.size());
            notifyListeners();
        }
    }

    public boolean contains(UUID playerId) {
        return players.containsKey(playerId);
    }

    public Object sessionToken(UUID playerId) { return players.get(playerId); }
    public void clear() { players.clear(); indi.mopelotus.musichud.network.PayloadFragments.resetServer(); notifyListeners(); }

    public List<IPlayerClient> players() {
        return List.copyOf(players.values());
    }

    public void addListener(Consumer<Collection<IPlayerClient>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        List<IPlayerClient> snapshot = players();
        listeners.forEach(listener -> listener.accept(snapshot));
    }

    @RegisterMark
    public static class Register implements ServerRegister {
        @Override
        public void register() {
            ICommonEventService.getInstance().registerCommonPlayerQuit(
                    ServerPlayerRegistry.getInstance()::disconnect);
            ICommonEventService.getInstance().registerCommonLifecycleStopping(ServerPlayerRegistry.getInstance()::clear);
        }
    }
}
