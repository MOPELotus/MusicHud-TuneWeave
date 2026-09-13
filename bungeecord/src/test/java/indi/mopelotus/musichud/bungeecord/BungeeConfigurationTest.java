package indi.mopelotus.musichud.bungeecord;

import indi.mopelotus.musichud.platform.plugin.bungeecord.config.BungeeServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BungeeConfigurationTest {
    @TempDir Path directory;

    @Test void voteRateBoundariesAndMalformedNumbersUseSharedPolicy() throws Exception {
        String[] values = {"NaN", "Infinity", "-Infinity", "oops", "-1", "2", "0.25"};
        double[] expected = {.5, .5, .5, .5, 0, 1, .25};
        var config = BungeeServerConfig.getInstance();
        for (int i = 0; i < values.length; i++) {
            Files.writeString(directory.resolve("config.properties"), "pusherVoteAdditionalRate=" + values[i] + "\n");
            config.initialize(directory);
            assertEquals(expected[i], config.getPusherVoteAdditionalRate(), values[i]);
            assertTrue(config.isConfigured());
            assertFalse(config.getStartupBinaryApiServerWhenLaunch());
        }
        assertThrows(UnsupportedOperationException.class, () -> config.setStartupBinaryApiServerWhenLaunch(true));
    }

    @Test void savePreservesUnrelatedKeysAndFreshDirectoryDoesNotInheritOldValues() throws Exception {
        var config = BungeeServerConfig.getInstance();
        Path file = directory.resolve("config.properties");
        Files.writeString(file, "pusherVoteAdditionalRate=0.7\noperatorNote=keep-me\n");
        config.initialize(directory);
        config.setPusherVoteAdditionalRate(.2);
        config.save();
        var saved = new Properties();
        try (var stream = Files.newInputStream(file)) { saved.load(stream); }
        assertEquals("keep-me", saved.getProperty("operatorNote"));
        assertEquals("0.2", saved.getProperty("pusherVoteAdditionalRate"));
        Path fresh = directory.resolve("fresh");
        config.initialize(fresh);
        assertEquals(.5, config.getPusherVoteAdditionalRate());
        assertTrue(Files.exists(fresh.resolve("config.properties")));
        Files.writeString(file, "pusherVoteAdditionalRate=" + "\\" + "uZZZZ\n");
        assertThrows(IllegalArgumentException.class, () -> config.initialize(directory));
    }
}
