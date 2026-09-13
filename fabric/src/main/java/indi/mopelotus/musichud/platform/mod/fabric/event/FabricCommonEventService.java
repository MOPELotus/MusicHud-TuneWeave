package indi.mopelotus.musichud.platform.mod.fabric.event;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.interfaces.ICommonEventService;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.platform.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class FabricCommonEventService implements ICommonEventService {
    private static volatile FabricCommonEventService instance;
    private final Set<Consumer<IPlayerClient>> disconnectListeners = new HashSet<>();
    private final Set<Runnable> stoppingListeners = new HashSet<>();

    private FabricCommonEventService() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            disconnectListeners.forEach(d -> d.accept(VanillaPlayerProxy.ofPlayer(handler.getPlayer())));
        });
        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.SERVER) {
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> stoppingListeners.forEach(Runnable::run));
        } else {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stoppingListeners.forEach(Runnable::run));
        }
    }

    @Override
    public Unregister registerCommonPlayerQuit(Consumer<IPlayerClient> listener) {
        disconnectListeners.add(listener);
        return () -> {
            disconnectListeners.remove(listener);
        };
    }

    @Override
    public Unregister registerCommonLifecycleStopping(Runnable listener) {
        stoppingListeners.add(listener);
        return () -> {
            stoppingListeners.remove(listener);
        };
    }

    public static FabricCommonEventService getInstance() {
        if (instance == null) {
            synchronized (FabricCommonEventService.class) {
                if (instance == null) {
                    instance = new FabricCommonEventService();
                }
            }
        }
        return instance;
    }
}