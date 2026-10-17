package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;

import java.util.function.Supplier;

public interface ServerConfig {
    static ServerConfig getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<ServerConfig> supplier = platform.getServerConfigSupplier();
        if (supplier != null) {
            ServerConfig serverConfig = supplier.get();
            if (serverConfig != null) {
                return serverConfig;
            }
        }
        throw new UnsupportedOperationException();
    }

    String getServerApiBaseUrl();

    void setServerApiBaseUrl(String serverApiBaseUrl);

    boolean getStartupBinaryApiServerWhenLaunch();

    void setStartupBinaryApiServerWhenLaunch(boolean startupBinaryApiServerWhenLaunch);

    String getServerApiBinaryExecutablePath();

    void setServerApiBinaryExecutablePath(String serverApiBinaryExecutablePath);

    double getPusherVoteAdditionalRate();

    void setPusherVoteAdditionalRate(double pusherVoteAdditionalRate);

    int getPort();

    void setPort(int port);

    void save();

    boolean isConfigured();

    void setConfigured(boolean configured);
}
