package indi.mopelotus.musichud.platform.plugin.paper.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static indi.mopelotus.musichud.platform.plugin.paper.deployment.PaperDeploymentGuard.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperDeploymentGuardTest {
    @BeforeEach void reset() {
        Global.INSTANCE.proxies.velocity.enabled = false;
        Spigot.bungee = false;
    }

    @Test void publicApiDistinguishesStandaloneOnlineModeFromProxyMode() {
        assertEquals(STANDALONE, inspect(new CurrentServer(false)));
        assertEquals(PROXY_BACKEND, inspect(new CurrentServer(true)));
    }

    @Test void oldestSupportedPaperReadsLiveVelocityAndBungeeSettings() {
        assertEquals(STANDALONE, inspect(new OldServer()));
        Global.INSTANCE.proxies.velocity.enabled = true;
        assertEquals(PROXY_BACKEND, inspect(new OldServer()));
        Global.INSTANCE.proxies.velocity.enabled = false;
        Spigot.bungee = true;
        assertEquals(PROXY_BACKEND, inspect(new OldServer()));
        Global.INSTANCE.proxies.velocity.enabled = true;
        assertEquals(PROXY_BACKEND, inspect(new OldServer()));
    }

    @Test void paper1215FallsBackWhenConfigurationHasOnlyProxyOnlineMode() {
        assertEquals(STANDALONE, inspect(new IntermediateServer()));
        Global.INSTANCE.proxies.velocity.enabled = true;
        assertEquals(PROXY_BACKEND, inspect(new IntermediateServer()));
    }

    @Test void missingOrFailingRuntimeConfigurationRemainsUnknown() {
        assertEquals(UNKNOWN, PaperDeploymentGuard.inspect(new OldServer(), name -> {
            throw new ClassNotFoundException(name);
        }));
        assertEquals(UNKNOWN, inspect(new FailingServer()));
    }

    @Test void backendAndUnknownDisableBeforeAnyServicesCanStart() {
        for (var mode : PaperDeploymentGuard.Mode.values()) {
            var errors = new ArrayList<String>();
            var disables = new AtomicInteger();
            boolean allowed = PaperDeploymentGuard.allow(mode, errors::add, disables::incrementAndGet);
            assertEquals(mode == STANDALONE, allowed);
            assertEquals(allowed ? 0 : 1, disables.get());
            assertEquals(allowed ? 0 : 1, errors.size());
            if (!allowed) assertTrue(errors.getFirst().contains("proxy"));
        }
    }

    private static PaperDeploymentGuard.Mode inspect(Object server) {
        return PaperDeploymentGuard.inspect(server, name -> switch (name) {
            case "io.papermc.paper.configuration.GlobalConfiguration" -> Global.class;
            case "org.spigotmc.SpigotConfig" -> Spigot.class;
            default -> throw new ClassNotFoundException(name);
        });
    }

    public record CurrentServer(boolean enabled) {
        public CurrentConfig getServerConfig() { return new CurrentConfig(enabled); }
    }
    public record CurrentConfig(boolean enabled) {
        public boolean isProxyEnabled() { return enabled; }
        public boolean isProxyOnlineMode() { return true; }
    }
    public static class OldServer {}
    public static class IntermediateServer {
        public OldConfig getServerConfig() { return new OldConfig(); }
    }
    public static class OldConfig { public boolean isProxyOnlineMode() { return true; } }
    public static class FailingServer {
        public CurrentConfig getServerConfig() { throw new IllegalStateException("unavailable"); }
    }
    public static class Global {
        static final Global INSTANCE = new Global();
        public final Proxies proxies = new Proxies();
        public static Global get() { return INSTANCE; }
    }
    public static class Proxies { public final Velocity velocity = new Velocity(); }
    public static class Velocity { public boolean enabled; }
    public static class Spigot { public static boolean bungee; }
}
