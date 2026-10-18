package indi.mopelotus.musichud.client.update;

import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.*;
import java.util.*;
import java.util.jar.JarFile;
import java.nio.file.Path;

/** Proves the target mod can use the selected service; process ownership is a separate permission. */
final class ModServiceCompatibility {
    enum Decision { COMPATIBLE, UNKNOWN, MANUAL_PAIR }
    private ModServiceCompatibility() {}
    static Decision decide(List<String> targetProfiles, Set<String> installedServiceProfiles) {
        if (targetProfiles.isEmpty() || installedServiceProfiles.isEmpty()) return Decision.UNKNOWN;
        return targetProfiles.stream().anyMatch(installedServiceProfiles::contains) ? Decision.COMPATIBLE : Decision.MANUAL_PAIR;
    }
    static Decision check(ClientUpdateCatalog.Offer offer) {
        if (offer.serviceProfiles().isEmpty()) return Decision.UNKNOWN;
        try {
            String url = ClientConfig.getInstance().getTuneWeaveBaseUrl();
            String version = ServiceUpdateHealth.configuredServerVersionAt(url).orElse(null);
            if (version == null) return Decision.UNKNOWN;
            var config = ServerConfig.getInstance();
            Path binary = Path.of(config.getServerApiBinaryExecutablePath()).toAbsolutePath().normalize();
            var manager = ApiServerManager.getInstance();
            if (manager != null && ApiBinaryUpdateService.usesManagedEndpoint(config, url)) {
                var process = manager.managedProcessSnapshot();
                if (process.owned() && binary.equals(process.executable())) {
                    Set<String> recorded = ApiBinaryUpdateService.getInstance().installedProfiles(binary, version);
                    if (!recorded.isEmpty()) return decide(offer.serviceProfiles(), recorded);
                }
            }
            Set<String> known = serviceProfiles(version, null);
            if (!known.isEmpty()) return decide(offer.serviceProfiles(), known);
            // Historical external releases carry the same publisher declaration as local installs.
            return decide(offer.serviceProfiles(), ServiceReleaseProfiles.forVersion(version));
        } catch (RuntimeException unavailable) { return Decision.UNKNOWN; }
    }
    static Set<String> serviceProfiles(String installedVersion, ApiServerFetcher.TuneWeaveManifest latest) {
        if (installedVersion == null) return Set.of();
        if (latest != null && installedVersion.equals(latest.getVersion()) && latest.getCompatibilityProfiles() != null)
            return Set.copyOf(latest.getCompatibilityProfiles());
        // Historical tested release predates publisher profile metadata.
        return TuneWeaveCompatibility.LEGACY_VERSION.equals(installedVersion) ? Set.of(TuneWeaveCompatibility.LEGACY_PROFILE) : Set.of();
    }
    record Selection(String url, String binary, long generation) {}
    static Selection selection() {
        var manager = ApiServerManager.getInstance();
        return new Selection(ClientConfig.getInstance().getTuneWeaveBaseUrl(),
                ServerConfig.getInstance().getServerApiBinaryExecutablePath(),
                manager == null ? -1 : manager.managedProcessSnapshot().generation());
    }
    static void requireUnchanged(ClientUpdateCatalog.Offer offer, Selection expected) {
        verifySelection(expected, selection());
        require(offer);
        verifySelection(expected, selection());
    }
    static void verifySelection(Selection expected, Selection actual) {
        if (!expected.equals(actual)) throw new IllegalStateException("Service selection changed during mod update");
    }
    static void require(ClientUpdateCatalog.Offer offer) {
        if (check(offer) != Decision.COMPATIBLE) throw new IllegalStateException("The target mod/service pair requires compatibility confirmation");
    }
    static void verifyDownloadedProfiles(Path jar, ClientUpdateCatalog.Offer offer) throws Exception {
        try (var archive = new JarFile(jar.toFile())) {
            var properties = new Properties();
            try (var input = archive.getInputStream(archive.getJarEntry(UpdateInstaller.METADATA))) { properties.load(input); }
            String declared = properties.getProperty("service_profiles", "");
            if (!Arrays.asList(declared.split(",", -1)).equals(offer.serviceProfiles()))
                throw new java.io.IOException("Downloaded JAR service profile mismatch");
        }
    }
}
