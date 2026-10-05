package indi.mopelotus.musichud.client.update;

import java.nio.file.Path;

/** CF checks releases and opens their web pages; it cannot install downloaded code. */
final class UpdateInstallation {
    private UpdateInstallation() {}
    static boolean enabled() { return false; }
    static void clean(Path jar, ClientUpdateCatalog.Installed installed) {}
    static void stage(Path jar, ClientUpdateCatalog.Installed installed, ClientUpdateCatalog.Offer offer) {
        throw new UnsupportedOperationException("CF updates are downloaded manually from the release page");
    }
}
