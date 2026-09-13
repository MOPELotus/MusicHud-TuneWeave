package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CfDistributionTest {
    @TempDir Path directory;

    @Test
    void acquisitionClassesAreAbsentFromTheRuntimeClasspath() {
        for (String name : new String[]{
                "indi.mopelotus.musichud.server.api.ApiServerFetcher",
                "indi.mopelotus.musichud.server.api.ApiBinaryUpdateService",
                "indi.mopelotus.musichud.client.ui.pages.ApiDownloadSession"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
        }
    }

    @Test
    void freshConfigRequiresUserSelectionAndExplicitStartupOptIn() throws Exception {
        var constructor = ServerConfigDefinition.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var config = constructor.newInstance();
        assertFalse(config.getStartupBinaryApiServerWhenLaunch());
        assertEquals("", config.getServerApiBinaryExecutablePath());
        config.setServerApiBinaryExecutablePath(directory.resolve("tuneweave").toString());
        config.setStartupBinaryApiServerWhenLaunch(true);
        assertTrue(config.getStartupBinaryApiServerWhenLaunch());
        assertEquals(directory.resolve("tuneweave").toString(), config.getServerApiBinaryExecutablePath());
    }

    @Test
    void startupLeavesExistingInstallationsAndUnrelatedFilesUntouched() throws Exception {
        Path active = Files.writeString(directory.resolve("tuneweave"), "active");
        Path previous = Files.writeString(directory.resolve("previous"), "previous");
        Path user = Files.writeString(directory.resolve("user-file"), "user");
        Path manifest = Files.writeString(directory.resolve("tuneweave-installations.json"),
                "{\"a\":{\"file\":\"tuneweave\"},\"b\":{\"file\":\"previous\"},\"bad\":{\"file\":\"../outside\"}}");
        String original = Files.readString(manifest);
        ApiBinaryMaintenance.afterStartup(active);
        ApiBinaryMaintenance.afterStartup(directory.resolve("missing"));
        assertEquals("active", Files.readString(active));
        assertEquals("previous", Files.readString(previous));
        assertEquals("user", Files.readString(user));
        assertEquals(original, Files.readString(manifest));
    }
}
