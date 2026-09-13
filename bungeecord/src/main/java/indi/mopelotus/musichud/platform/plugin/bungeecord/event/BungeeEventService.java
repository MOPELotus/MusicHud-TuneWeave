package indi.mopelotus.musichud.platform.plugin.bungeecord.event;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import indi.mopelotus.musichud.interfaces.ICommonEventService;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeePlayerProxy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class BungeeEventService implements ICommonEventService {
    private static final BungeeEventService INSTANCE = new BungeeEventService();

    private final Set<Consumer<IPlayerClient>> disconnectListeners = ConcurrentHashMap.newKeySet();
    private final Set<Runnable> stoppingListeners = ConcurrentHashMap.newKeySet();

    private BungeeEventService() {
    }

    public static BungeeEventService getInstance() {
        return INSTANCE;
    }

    @Override
    public Unregister registerCommonPlayerQuit(Consumer<IPlayerClient> listener) {
        disconnectListeners.add(listener);
        return () -> disconnectListeners.remove(listener);
    }

    @Override
    public Unregister registerCommonLifecycleStopping(Runnable listener) {
        stoppingListeners.add(listener);
        return () -> stoppingListeners.remove(listener);
    }

    public void firePlayerQuit(ProxiedPlayer player) {
        BungeePlayerProxy.disconnect(player);
        indi.mopelotus.musichud.platform.plugin.bungeecord.network.BungeeNetworkManager.getInstance().cancelPending(player);
        IPlayerClient client = BungeePlayerProxy.of(player);
        disconnectListeners.forEach(listener -> listener.accept(client));
    }

    public void fireProxyStopping() {
        stoppingListeners.forEach(Runnable::run);
    }
}
