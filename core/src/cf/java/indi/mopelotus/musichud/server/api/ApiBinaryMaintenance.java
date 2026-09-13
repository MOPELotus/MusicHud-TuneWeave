package indi.mopelotus.musichud.server.api;

import java.nio.file.Path;

/** User-installed executables are never installed, updated or pruned by CF. */
final class ApiBinaryMaintenance {
    private ApiBinaryMaintenance() {}

    static void afterStartup(Path executable) {
        // The local program and any previous installations remain user-owned.
    }
}
