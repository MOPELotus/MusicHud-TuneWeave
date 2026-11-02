package indi.mopelotus.musichud.client.companion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

/** Bounded, all-or-nothing loading; retains Properties' ISO-8859-1 and escape semantics. */
final class CompanionSettingsFile {
    static final int MAX_BYTES = 1024 * 1024;

    private CompanionSettingsFile() {}

    static Properties load(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Properties();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) return new Properties();
                Properties parsed = new Properties();
                parsed.load(new ByteArrayInputStream(bytes));
                return parsed;
            }
        } catch (IOException | IllegalArgumentException failure) {
            // Loading a damaged file must not poison the manager's static initialization.
            return new Properties();
        }
    }
}
