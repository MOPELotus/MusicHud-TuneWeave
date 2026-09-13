package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ServerConfigDefinition implements ServerConfig {
    private static final String FILE_NAME = ProjectIdentity.CONFIG_PREFIX + "-server.toml";
    @Getter
    private static final ServerConfigDefinition instance = new ServerConfigDefinition();

    private String serverApiBaseUrl = "http://127.0.0.1:7832";
    private boolean startupBinaryApiServerWhenLaunch = true;
    private String serverApiBinaryExecutablePath = "musichud-tuneweave/tuneweave";
    private double pusherVoteAdditionalRate = 0.5;
    private int port = 7832;
    @Setter
    @Getter
    private boolean configured;

    private ServerConfigDefinition() {
    }

    public synchronized void load() {
        Path path = SimpleTomlConfig.path(FILE_NAME);
        Map<String, String> values = SimpleTomlConfig.read(path);
        serverApiBaseUrl = SimpleTomlConfig.getString(values, "serverApiBaseUrl", serverApiBaseUrl);
        startupBinaryApiServerWhenLaunch = SimpleTomlConfig.getBoolean(
                values,
                "startupBinaryApiServerWhenLaunch",
                startupBinaryApiServerWhenLaunch
        );
        serverApiBinaryExecutablePath = SimpleTomlConfig.getString(
                values,
                "serverApiBinaryExecutablePath",
                serverApiBinaryExecutablePath
        );
        pusherVoteAdditionalRate = clamp(SimpleTomlConfig.getDouble(
                values,
                "pusherVoteAdditionalRate",
                pusherVoteAdditionalRate
        ));
        port = clampPort(SimpleTomlConfig.getInt(values, "port", port));
        configured = true;
        save();
    }

    @Override
    public void setServerApiBaseUrl(String serverApiBaseUrl) {
        this.serverApiBaseUrl = serverApiBaseUrl;
    }

    @Override
    public void setStartupBinaryApiServerWhenLaunch(boolean startupBinaryApiServerWhenLaunch) {
        this.startupBinaryApiServerWhenLaunch = startupBinaryApiServerWhenLaunch;
    }

    @Override
    public void setServerApiBinaryExecutablePath(String serverApiBinaryExecutablePath) {
        this.serverApiBinaryExecutablePath = serverApiBinaryExecutablePath;
    }

    @Override
    public void setPusherVoteAdditionalRate(double pusherVoteAdditionalRate) {
        this.pusherVoteAdditionalRate = clamp(pusherVoteAdditionalRate);
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public void setPort(int port) {
        this.port = clampPort(port);
    }

    @Override
    public String getServerApiBaseUrl() {
        return serverApiBaseUrl;
    }

    @Override
    public boolean getStartupBinaryApiServerWhenLaunch() {
        return startupBinaryApiServerWhenLaunch;
    }

    @Override
    public String getServerApiBinaryExecutablePath() {
        return serverApiBinaryExecutablePath;
    }

    @Override
    public double getPusherVoteAdditionalRate() {
        return pusherVoteAdditionalRate;
    }

    @Override
    public synchronized void save() {
        SimpleTomlConfig.write(SimpleTomlConfig.path(FILE_NAME), List.of(
                new SimpleTomlConfig.Entry("serverApiBaseUrl", "TuneWeave base URL configuration", serverApiBaseUrl),
                new SimpleTomlConfig.Entry("startupBinaryApiServerWhenLaunch", "Start TuneWeave when game launches", startupBinaryApiServerWhenLaunch),
                new SimpleTomlConfig.Entry("serverApiBinaryExecutablePath", "TuneWeave server executable path", serverApiBinaryExecutablePath),
                new SimpleTomlConfig.Entry("pusherVoteAdditionalRate", "Skip vote threshold added by the current pusher (0.0 - 1.0)", pusherVoteAdditionalRate),
                new SimpleTomlConfig.Entry("port", "TuneWeave listening port", port)
        ));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int clampPort(int value) {
        return Math.max(1, Math.min(65535, value));
    }
}
