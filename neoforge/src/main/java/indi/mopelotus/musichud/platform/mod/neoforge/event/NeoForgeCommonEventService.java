package indi.mopelotus.musichud.platform.mod.neoforge.event;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.interfaces.ICommonEventService;
import indi.mopelotus.musichud.interfaces.Unregister;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.platform.Environment;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class NeoForgeCommonEventService implements ICommonEventService {
    private static volatile NeoForgeCommonEventService instance;
    private final Set<Consumer<IPlayerClient>> disconnectListeners = new HashSet<>();
    private final Set<Runnable> stoppingListeners = new HashSet<>();

    private NeoForgeCommonEventService() {
        NeoForge.EVENT_BUS.register(this);
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

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof Player player) {
            disconnectListeners.forEach(d -> d.accept(VanillaPlayerProxy.ofPlayer(player)));
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.SERVER) {
            stoppingListeners.forEach(Runnable::run);
        }
    }

    @SubscribeEvent
    public void onClientStopping(ClientStoppingEvent event) {
        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
            stoppingListeners.forEach(Runnable::run);
        }
    }

    public static NeoForgeCommonEventService getInstance() {
        if (instance == null) {
            synchronized (NeoForgeCommonEventService.class) {
                if (instance == null) {
                    instance = new NeoForgeCommonEventService();
                }
            }
        }
        return instance;
    }
}