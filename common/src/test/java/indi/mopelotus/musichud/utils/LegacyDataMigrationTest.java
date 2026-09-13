package indi.mopelotus.musichud.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyDataMigrationTest {
    @TempDir Path directory;

    @Test void resolvesOwnedConfigPathWithoutLegacyCopy() {
        Path path = LegacyDataMigration.configFile(directory, "musichud_tuneweave-client.toml");
        assertEquals(directory.resolve("musichud_tuneweave-client.toml"), path);
        assertFalse(Files.exists(directory.resolve("music_hud-client.toml")));
    }

    @Test void resolvesOwnedUniPath() {
        assertEquals(directory.resolve("musichud-tuneweave/uni-playlists.json"), LegacyDataMigration.uniPlaylists(directory));
    }

    @Test void rejectsBlankConfigName() {
        assertThrows(IllegalArgumentException.class, () -> LegacyDataMigration.configFile(directory, ""));
    }
}
