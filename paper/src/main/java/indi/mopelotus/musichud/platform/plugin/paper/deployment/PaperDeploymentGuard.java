package indi.mopelotus.musichud.platform.plugin.paper.deployment;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Reads effective server settings, including servers using a custom configuration directory. */
public final class PaperDeploymentGuard {
    public enum Mode { STANDALONE, PROXY_BACKEND, UNKNOWN }

    @FunctionalInterface
    public interface ClassLookup {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    private PaperDeploymentGuard() {}

    public static Mode inspect(Object server, ClassLookup classes) {
        try {
            try {
                Method configuration = server.getClass().getMethod("getServerConfig");
                Method proxyEnabled = configuration.getReturnType().getMethod("isProxyEnabled");
                return mode((Boolean) proxyEnabled.invoke(configuration.invoke(server)));
            } catch (NoSuchMethodException unavailableOnOlderPaper) {
                // Paper 1.21.1–1.21.5 has no public isProxyEnabled API. Read the same live
                // fields used by newer Paper, rather than rereading a guessed YAML path.
                Class<?> global = classes.load("io.papermc.paper.configuration.GlobalConfiguration");
                Object config = global.getMethod("get").invoke(null);
                Object proxies = global.getField("proxies").get(config);
                Object velocity = proxies.getClass().getField("velocity").get(proxies);
                boolean modernForwarding = velocity.getClass().getField("enabled").getBoolean(velocity);
                boolean legacyForwarding = classes.load("org.spigotmc.SpigotConfig").getField("bungee").getBoolean(null);
                return mode(modernForwarding || legacyForwarding);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Mode.UNKNOWN;
        }
    }

    private static Mode mode(boolean proxy) {
        return proxy ? Mode.PROXY_BACKEND : Mode.STANDALONE;
    }

    public static boolean allow(Mode mode, Consumer<String> error, Runnable disable) {
        if (mode == Mode.STANDALONE) return true;
        error.accept(mode == Mode.PROXY_BACKEND
                ? "MusicHud TuneWeave disabled: this server is configured as a proxy backend. "
                    + "Install MusicHud TuneWeave only on the proxy and remove it from ALL backend servers. "
                    + "Proxy/backend double installation is prohibited."
                : "MusicHud TuneWeave disabled: unable to determine the server's active proxy configuration. "
                    + "No playback services were started. Check this Paper version before enabling the plugin; "
                    + "proxy networks must install it only on the proxy.");
        disable.run();
        return false;
    }
}
