package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.interfaces.ServerConfig;
import java.nio.file.Path;

final class ClientPrivatePaths {
    static Path accountsFile() {
        String configured = ServerConfig.getInstance().getServerApiBinaryExecutablePath();
        Path executable = configured == null || configured.isBlank() ? Path.of("musichud-tuneweave/tuneweave") : Path.of(configured);
        // ApiServerManager also resolves executable paths against the game process working directory.
        Path absolute = executable.toAbsolutePath();
        return absolute.normalize().getParent().resolve("client-accounts.json");
    }
}
