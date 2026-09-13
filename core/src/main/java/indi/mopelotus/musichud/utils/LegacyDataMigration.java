package indi.mopelotus.musichud.utils;

import java.nio.file.Path;

/** Resolves project-owned configuration paths. */
public final class LegacyDataMigration {
    private LegacyDataMigration() {}

    public static Path configFile(Path root, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Unknown config file");
        return root.resolve(name).normalize();
    }

    public static Path uniPlaylists(Path root) {
        return root.resolve("musichud-tuneweave/uni-playlists.json").normalize();
    }
}
