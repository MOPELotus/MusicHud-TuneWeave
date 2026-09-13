package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.platform.Environment;

import java.util.function.Supplier;

public interface IClientNetworkService {
    <T extends C2SPayload> void sendToServer(T payload);

    static IClientNetworkService getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<IClientNetworkService> supplier = platform.getClientNetworkServiceSupplier();
        if (supplier != null) {
            IClientNetworkService clientNetworkService = supplier.get();
            if (clientNetworkService != null) {
                return clientNetworkService;
            }
        }
        throw new UnsupportedOperationException();
    }
}
