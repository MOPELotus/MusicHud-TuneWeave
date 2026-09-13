package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.platform.Environment;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface ICommonEventService {
    static ICommonEventService getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<ICommonEventService> supplier = platform.getServerEventServiceSupplier();
        if (supplier != null) {
            ICommonEventService iCommonEventService = supplier.get();
            if (iCommonEventService != null) {
                return iCommonEventService;
            }
        }
        throw new UnsupportedOperationException();
    }

    Unregister registerCommonPlayerQuit(Consumer<IPlayerClient> listener);
    Unregister registerCommonLifecycleStopping(Runnable listener);
}
