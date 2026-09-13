package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IClientLoginService {
    static IClientLoginService getInstance() {
        Environment currentEnvironment = MusicHud.getCurrentEnvironment();
        if (currentEnvironment.getSide() == Environment.Side.CLIENT) {
            Environment.Platform platform = currentEnvironment.getPlatform();
            Supplier<IClientLoginService> supplier = platform.getClientLoginServiceSupplier();
            if (supplier != null) {
                IClientLoginService iClientLoginService = supplier.get();
                if (iClientLoginService != null) {
                    return iClientLoginService;
                }
            }
        }
        throw new UnsupportedOperationException();
    }

    enum LoginState {
        UNLOGGED,
        ANONYMOUS,
        LOGGED_IN
    }

    boolean isLogined();

    LoginState getLoginState();

    Unregister addLoginStateListener(Consumer<LoginState> listener);

    boolean hasStoredSession();

    void restoreSession();

    void logout();
}
