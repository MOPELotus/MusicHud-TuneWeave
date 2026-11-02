package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import java.nio.file.Path;

final class ClientPrivatePaths {
    static Path accountsFile() {
        return MusicHud.getConfigDirectory().toAbsolutePath().normalize().getParent()
                .resolve("musichud-tuneweave/client-accounts.json");
    }
}
