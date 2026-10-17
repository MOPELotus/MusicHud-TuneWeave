package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CfDistributionTest {
    @TempDir Path directory;
    @Test void programAcquisitionAndProcessManagementClassesAreAbsent() {
        for (String name : new String[]{"ApiServerFetcher", "ApiBinaryUpdateService", "ApiBinaryMaintenance", "ApiServerManager", "SodaSignerSupport", "SodaSignerProcess"})
            assertThrows(ClassNotFoundException.class, () -> Class.forName("indi.mopelotus.musichud.server.api." + name));
        for (String name : new String[]{"ApiDownloadSession", "ApiDistributionUi"})
            assertThrows(ClassNotFoundException.class, () -> Class.forName("indi.mopelotus.musichud.client.ui.pages." + name));
    }
    @Test void executableConfigurationIsAbsentFromTheCfContract() {
        for (Class<?> type : new Class<?>[]{ServerConfig.class, ServerConfigDefinition.class}) {
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("getStartupBinaryApiServerWhenLaunch"));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("setStartupBinaryApiServerWhenLaunch", boolean.class));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("getServerApiBinaryExecutablePath"));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("setServerApiBinaryExecutablePath", String.class));
        }
    }
    @Test void cfConnectionConfigPreservesStandardConfigAndExistingPrograms() throws Exception {
        Path active = Files.writeString(directory.resolve("tuneweave"), "active");
        Path user = Files.writeString(directory.resolve("user-file"), "user");
        Path standard = Files.writeString(directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-server.toml"),
                "startupBinaryApiServerWhenLaunch = true\nserverApiBinaryExecutablePath = \"tuneweave\"\n");
        String original = Files.readString(standard);
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(directory);
        try {
            var constructor = ServerConfigDefinition.class.getDeclaredConstructor(); constructor.setAccessible(true);
            var config = constructor.newInstance(); config.load();
            config.setServerApiBaseUrl("http://127.0.0.1:18732"); config.save();
            String saved = Files.readString(directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-cf-server.toml"));
            assertTrue(saved.contains("http://127.0.0.1:18732"));
            assertFalse(saved.contains("Executable")); assertFalse(saved.contains("startupBinary"));
            assertEquals(original, Files.readString(standard));
            assertEquals("active", Files.readString(active)); assertEquals("user", Files.readString(user));
        } finally { MusicHud.setConfigDirectory(previous); }
    }
}
