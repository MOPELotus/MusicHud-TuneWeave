package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.platform.Environment;

import java.util.Collection;
import java.util.function.Supplier;

public interface IServerNetworkService {
    static IServerNetworkService getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<IServerNetworkService> supplier = platform.getServerNetworkServiceSupplier();
        if (supplier != null) {
            IServerNetworkService serverNetworkService = supplier.get();
            if (serverNetworkService != null) {
                return serverNetworkService;
            }
        }
        throw new UnsupportedOperationException();
    }

    <T extends S2CPayload> void sendToPlayer(IPlayerClient player, T payload);

    default void sendToPlayers(Collection<IPlayerClient> players, S2CPayload payload) {
        for (IPlayerClient player : players) {
            sendToPlayer(player, payload);
        }
    }

}
