package indi.mopelotus.musichud.server.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBinaryUpdateServiceTest {
    private final ApiBinaryUpdateService service = ApiBinaryUpdateService.getInstance();

    @Test
    void successfulSwitchDeletesOnlyObsoleteManagedBinary(@TempDir Path installDir) throws IOException {
        Path oldBinary = Files.writeString(installDir.resolve("tuneweave-old.exe"), "old");
        Path activeBinary = Files.writeString(installDir.resolve("tuneweave.exe"), "current");
        Path userFile = Files.writeString(installDir.resolve("keep-me.exe"), "user-owned");
        service.recordManagedInstallation(installDir, "v1", "1.0.0", oldBinary.getFileName().toString());
        service.recordManagedInstallation(installDir, "v2", "2.0.0", activeBinary.getFileName().toString());

        ApiBinaryUpdateService.CleanupReport report =
                service.cleanupObsoleteManagedBinaries(installDir, activeBinary);

        assertEquals(1, report.deleted());
        assertFalse(Files.exists(oldBinary));
        assertTrue(Files.exists(activeBinary));
        assertTrue(Files.exists(userFile));
        assertNull(service.findInstalledVersion(installDir, "v1"));
        assertEquals("2.0.0", service.findInstalledVersion(installDir, "v2"));
    }

    @Test
    void missingActiveBinarySkipsCleanupAndPreservesRollback(@TempDir Path installDir) throws IOException {
        Path oldBinary = Files.writeString(installDir.resolve("tuneweave-old"), "old");
        Path missingActive = Files.writeString(installDir.resolve("tuneweave-new"), "new");
        service.recordManagedInstallation(installDir, "v1", "1.0.0", oldBinary.getFileName().toString());
        service.recordManagedInstallation(installDir, "v2", "2.0.0", missingActive.getFileName().toString());
        Files.delete(missingActive);

        ApiBinaryUpdateService.CleanupReport report =
                service.cleanupObsoleteManagedBinaries(installDir, missingActive);

        assertTrue(report.skipped());
        assertTrue(Files.exists(oldBinary));
        assertEquals("1.0.0", service.findInstalledVersion(installDir, "v1"));
    }

    @Test
    void untrackedActiveBinarySkipsCleanupAndPreservesRollback(@TempDir Path installDir) throws IOException {
        Path oldBinary = Files.writeString(installDir.resolve("tuneweave-old"), "old");
        Path untrackedActive = Files.writeString(installDir.resolve("custom-tuneweave"), "current");
        service.recordManagedInstallation(installDir, "v1", "1.0.0", oldBinary.getFileName().toString());

        ApiBinaryUpdateService.CleanupReport report =
                service.cleanupObsoleteManagedBinaries(installDir, untrackedActive);

        assertTrue(report.skipped());
        assertTrue(Files.exists(oldBinary));
        assertTrue(Files.exists(untrackedActive));
    }

    @Test
    void deletionFailureIsRetainedForNextRetry(@TempDir Path installDir) throws IOException {
        Path oldBinary = Files.writeString(installDir.resolve("tuneweave-old"), "old");
        Path activeBinary = Files.writeString(installDir.resolve("tuneweave"), "current");
        service.recordManagedInstallation(installDir, "v1", "1.0.0", oldBinary.getFileName().toString());
        service.recordManagedInstallation(installDir, "v2", "2.0.0", activeBinary.getFileName().toString());

        ApiBinaryUpdateService.CleanupReport report = service.cleanupObsoleteManagedBinaries(
                installDir, activeBinary, path -> {
                    throw new IOException("simulated file lock");
                });

        assertEquals(1, report.failed());
        assertTrue(Files.exists(oldBinary));
        assertEquals("1.0.0", service.findInstalledVersion(installDir, "v1"));
    }

    @Test
    void unsafeManifestPathIsRejected(@TempDir Path tempRoot) throws IOException {
        Path installDir = Files.createDirectory(tempRoot.resolve("install"));
        Path activeBinary = Files.writeString(installDir.resolve("tuneweave"), "current");
        Path outside = Files.writeString(tempRoot.resolve("outside-owned-file"), "user-owned");
        Files.writeString(installDir.resolve(ApiBinaryUpdateService.INSTALLATIONS_FILE_NAME), """
                {
                  "current": {"version": "2.0.0", "file": "tuneweave"},
                  "unsafe": {"version": "1.0.0", "file": "../outside-owned-file"}
                }
                """);

        ApiBinaryUpdateService.CleanupReport report =
                service.cleanupObsoleteManagedBinaries(installDir, activeBinary);

        assertEquals(1, report.rejected());
        assertTrue(Files.exists(outside));
    }
    @Test
    void installNeverReplacesExistingBinary(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("tuneweave"), "running-or-user-file");
        Path temp = Files.writeString(dir.resolve("tuneweave.v2.temp"), "new");
        Path installed = service.resolveFinalPath(temp, "v2");
        assertEquals("running-or-user-file", Files.readString(old));
        assertEquals("new", Files.readString(installed));
        assertFalse(old.equals(installed));
    }

    @Test
    void remoteAndUnmanagedServicesAreNeverOfferedUpdates(@TempDir Path dir) throws IOException {
        Path custom = Files.writeString(dir.resolve("custom"), "user");
        var manifest = manifest("2.0.0", "v2");
        assertEquals(ApiBinaryUpdateService.CheckStatus.UNMANAGED,
                service.inspectManagedUpdate(custom.toString(), "http://127.0.0.1:7832", manifest).status());
        service.recordManagedInstallation(dir, "v1", "1.0.0", "custom");
        assertEquals(ApiBinaryUpdateService.CheckStatus.UNMANAGED,
                service.inspectManagedUpdate(custom.toString(), "https://external.example", manifest).status());
        assertFalse(ApiBinaryUpdateService.isLocalServiceUrl("http://localhost@external.example"));
        assertFalse(ApiBinaryUpdateService.isLocalServiceUrl("file:///tmp/service"));
        assertFalse(ApiBinaryUpdateService.isLocalServiceUrl("http://127.0.0.1?token=secret"));
        assertTrue(ApiBinaryUpdateService.isLocalServiceUrl("http://[::1]:7832"));
    }

    @Test
    void targetFrozenAndStagingSurvivesCleanupWithoutActivating(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("old"), "old");
        service.recordManagedInstallation(dir, "v1", "1.0.0", "old");
        var checked = service.inspectManagedUpdate(old.toString(), "http://localhost:7832", manifest("2.0.0", "v2"));
        assertEquals(ApiBinaryUpdateService.CheckStatus.AVAILABLE, checked.status());
        var mutated = checked.offer().manifest();
        // Returning a manifest never exposes the offer's stored target to caller mutation.
        JsonUtilHolder.mutateVersion(mutated, "100.0.0");
        assertEquals("2.0.0", checked.offer().manifest().getVersion());
        Path temp = Files.writeString(dir.resolve("tuneweave.v2.temp"), "new");
        var staged = service.stageDownloaded(checked.offer(), new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", temp));
        assertEquals(64, staged.sha256().length());
        assertNull(service.findInstalledVersion(dir, "v2"));
        service.cleanupObsoleteManagedBinaries(dir, old);
        assertTrue(Files.exists(staged.binary()));
        assertEquals("old", Files.readString(old));
        assertFalse(service.activateStaged(staged, config(old), "https://remote.example"));
        assertTrue(service.activateStaged(staged, config(old), "http://localhost:7832"));
        assertEquals("2.0.0", service.findInstalledVersion(dir, "v2"));
        assertTrue(Files.exists(old), "activation itself preserves rollback binary");
    }

    @Test
    void staleSelectionAndChangedStagedBytesCannotActivate(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("old"), "old");
        Path another = Files.writeString(dir.resolve("custom"), "unrelated");
        service.recordManagedInstallation(dir, "v1", "1.0.0", "old");
        var offer = service.inspectManagedUpdate(old.toString(), "http://localhost", manifest("2.0.0", "v2")).offer();
        Path temp = Files.writeString(dir.resolve("tuneweave.v2.temp"), "new");
        var staged = service.stageDownloaded(offer, new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", temp));
        assertFalse(service.activateStaged(staged, config(another), "http://localhost"));
        Files.writeString(staged.binary(), "tampered");
        assertFalse(service.activateStaged(staged, config(old), "http://localhost"));
        assertNull(service.findInstalledVersion(dir, "v2"));
        assertEquals("unrelated", Files.readString(another));
    }

    @Test
    void symlinkAndWrongDownloadedTargetAreRejected(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("old"), "old");
        service.recordManagedInstallation(dir, "v1", "1.0.0", "old");
        var offer = service.inspectManagedUpdate(old.toString(), "http://localhost", manifest("2.0.0", "v2")).offer();
        Path temp = Files.writeString(dir.resolve("tuneweave.v3.temp"), "wrong");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.stageDownloaded(
                offer, new ApiBinaryUpdateService.DownloadedRelease("v3", "3.0.0", temp)));
        Path link = Files.createSymbolicLink(dir.resolve("link"), old);
        assertEquals(ApiBinaryUpdateService.CheckStatus.UNMANAGED,
                service.inspectManagedUpdate(link.toString(), "http://localhost", manifest("2.0.0", "v2")).status());
    }

    @Test
    void verifiedRestartRequiresOwnedNewProcessAndExactVersion(@TempDir Path dir) throws IOException {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        service.recordManagedInstallation(dir, "v1", "1.0.0", previous.getFileName().toString());
        fixture.relativeSelection = true;
        fixture.configuredPath = Path.of("").toAbsolutePath().normalize().relativize(previous).toString();
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        var staged = stageFor(previous, dir);
        fixture.cleanupCallback = active -> service.cleanupObsoleteManagedBinaries(dir, active);
        fixture.healthy = false;
        long[] launched = {-1};
        var result = service.activateAndRestartVerified(staged, fixture.config, () -> "http://localhost:3000",
                fixture.manager, url -> {
                    assertTrue(Files.exists(previous), "startup cleanup must retain rollback until version is confirmed");
                    return java.util.Optional.of("2.0.0");
                }, millis -> {
                    long generation = fixture.manager.managedProcessSnapshot().generation();
                    if (launched[0] != generation) {
                        fixture.launchHealthy();
                        launched[0] = generation;
                    }
                });
        assertEquals(ApiBinaryUpdateService.ActivationStatus.ACTIVE, result);
        assertEquals(staged.binary(), fixture.manager.managedProcessSnapshot().executable());
        assertTrue(fixture.manager.managedProcessSnapshot().owned());
    }

    @Test
    void wrongHealthyVersionRollsBackWithoutDeletingOldBinary(@TempDir Path dir) throws IOException {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        service.recordManagedInstallation(dir, "v1", "1.0.0", previous.getFileName().toString());
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        var staged = stageFor(previous, dir);
        fixture.cleanupCallback = active -> service.cleanupObsoleteManagedBinaries(dir, active);
        fixture.healthy = false;
        long[] launched = {-1};
        var result = service.activateAndRestartVerified(staged, fixture.config, () -> "http://localhost:3000",
                fixture.manager, url -> java.util.Optional.of("1.0.0"), millis -> {
                    long generation = fixture.manager.managedProcessSnapshot().generation();
                    if (launched[0] != generation) {
                        fixture.launchHealthy();
                        launched[0] = generation;
                        fixture.healthy = false; // next-generation prelaunch must not mistake the old response for an external service
                    }
                });
        assertEquals(ApiBinaryUpdateService.ActivationStatus.ROLLED_BACK, result);
        assertEquals(previous, Path.of(fixture.configuredPath));
        assertTrue(Files.exists(previous));
    }

    @Test
    void stoppedServiceOnlySelectsNextStartAndExternalProcessCannotBeRestarted(@TempDir Path dir) throws IOException {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        service.recordManagedInstallation(dir, "v1", "1.0.0", previous.getFileName().toString());
        var staged = stageFor(previous, dir);
        try (var available = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            fixture.port = available.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + fixture.port;
        assertEquals(ApiBinaryUpdateService.ActivationStatus.NEXT_START,
                service.activateAndRestartVerified(staged, fixture.config, () -> endpoint, fixture.manager,
                        url -> { throw new AssertionError("Stopped service cannot report running version"); }, millis -> {}));
        assertEquals(0, fixture.launchCalls);
        fixture.configuredPath = previous.toString();
        fixture.healthy = true;
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertEquals(ApiServerManager.BinaryApiServerStatus.RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertFalse(fixture.manager.managedProcessSnapshot().owned());
        assertEquals(ApiBinaryUpdateService.ActivationStatus.FAILED,
                service.activateAndRestartVerified(staged, fixture.config, () -> endpoint, fixture.manager,
                        url -> java.util.Optional.of("2.0.0"), millis -> {}));
        assertEquals(0, fixture.launchCalls);
    }

    @Test
    void lateHealthReplyCannotOverwriteNewUserSelection(@TempDir Path dir) throws IOException {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        service.recordManagedInstallation(dir, "v1", "1.0.0", previous.getFileName().toString());
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        var staged = stageFor(previous, dir);
        Path custom = Files.writeString(dir.resolve("user-choice"), "custom");
        fixture.healthy = false;
        long[] launched = {-1};
        var result = service.activateAndRestartVerified(staged, fixture.config, () -> "http://localhost:3000",
                fixture.manager, url -> {
                    fixture.configuredPath = custom.toString();
                    fixture.manager.stopApiServer();
                    return java.util.Optional.of("2.0.0");
                }, millis -> {
                    long generation = fixture.manager.managedProcessSnapshot().generation();
                    if (launched[0] != generation) {
                        fixture.launchHealthy();
                        launched[0] = generation;
                    }
                });
        assertEquals(ApiBinaryUpdateService.ActivationStatus.SUPERSEDED, result);
        assertEquals(custom.toString(), fixture.configuredPath);
        assertEquals("custom", Files.readString(custom));
    }

    private ApiBinaryUpdateService.StagedUpdate stageFor(Path previous, Path dir) throws IOException {
        var offer = service.inspectManagedUpdate(previous.toString(), "http://localhost", manifest("2.0.0", "v2")).offer();
        Path temp = Files.writeString(dir.resolve("tuneweave.v2.temp"), "new");
        temp.toFile().setExecutable(true);
        return service.stageDownloaded(offer, new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", temp));
    }

    @Test
    void predictableOldTemporaryManifestFileIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path binary = Files.writeString(dir.resolve("tuneweave"), "binary");
        Path unrelated = Files.writeString(dir.resolve(ApiBinaryUpdateService.INSTALLATIONS_FILE_NAME + ".tmp"), "user-owned");
        assertTrue(service.recordManagedInstallation(dir, "v1", "1.0.0", binary.getFileName().toString()));
        assertEquals("user-owned", Files.readString(unrelated));
    }

    @Test
    void wrongLocalPortCannotActivateManagedService(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("old"), "old");
        service.recordManagedInstallation(dir, "v1", "1.0.0", "old");
        var staged = stageFor(old, dir);
        assertFalse(service.activateStaged(staged, config(old), "http://localhost:9999"));
        assertNull(service.findInstalledVersion(dir, "v2"));
    }

    @Test
    void publisherProfileEvidenceBindsFileBytesAndActualHealthVersion(@TempDir Path dir) throws IOException {
        Path binary = Files.writeString(dir.resolve("tuneweave"), "verified-download");
        var mutable = new java.util.ArrayList<>(java.util.List.of("tuneweave-http-v1"));
        var release = new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", dir.resolve("download.temp"), null, mutable);
        mutable.clear();
        assertEquals(java.util.List.of("tuneweave-http-v1"), release.profiles());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> release.profiles().add("changed"));
        assertTrue(service.recordManagedInstallation(dir, release, binary.getFileName().toString()));
        var profiles = service.installedProfiles(binary, "2.0.0");
        assertEquals(java.util.Set.of("tuneweave-http-v1"), profiles);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> profiles.add("changed"));
        assertTrue(service.installedProfiles(binary, "3.0.0").isEmpty());
        Files.writeString(binary, "replaced-by-user");
        assertTrue(service.installedProfiles(binary, "2.0.0").isEmpty());
        assertEquals("replaced-by-user", Files.readString(binary));
    }

    @Test
    void legacyAndSymlinkInstallationsNeverInventProfileEvidence(@TempDir Path dir) throws IOException {
        Path binary = Files.writeString(dir.resolve("tuneweave"), "legacy");
        assertTrue(service.recordManagedInstallation(dir, "v1", "1.0.0", "tuneweave"));
        assertTrue(service.installedProfiles(binary, "1.0.0").isEmpty());
        var release = new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", binary, null,
                java.util.List.of("tuneweave-http-v1"));
        assertTrue(service.recordManagedInstallation(dir, release, "tuneweave"));
        Path userFile = Files.writeString(dir.resolve("user"), "legacy");
        Files.delete(binary);
        Files.createSymbolicLink(binary, userFile);
        assertTrue(service.installedProfiles(binary, "2.0.0").isEmpty());
        assertFalse(service.recordManagedInstallation(dir, release, "tuneweave"));
        assertEquals("legacy", Files.readString(userFile));
    }

    @Test
    void stagedActivationPersistsTheFrozenPublisherProfiles(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("old"), "old");
        service.recordManagedInstallation(dir, "v1", "1.0.0", "old");
        var manifest = indi.mopelotus.musichud.utils.JsonUtil.gson.fromJson(
                "{\"version\":\"2.0.0\",\"tag\":\"v2\",\"compatibility_profiles\":[\"tuneweave-http-v1\"]}",
                ApiServerFetcher.TuneWeaveManifest.class);
        var offer = service.inspectManagedUpdate(old.toString(), "http://localhost:7832", manifest).offer();
        Path temp = Files.writeString(dir.resolve("tuneweave.v2.temp"), "new");
        var staged = service.stageDownloaded(offer, new ApiBinaryUpdateService.DownloadedRelease("v2", "2.0.0", temp));
        assertTrue(service.activateStaged(staged, config(old), "http://localhost:7832"));
        assertEquals(java.util.Set.of("tuneweave-http-v1"), service.installedProfiles(staged.binary(), "2.0.0"));
    }

    @Test
    void changedHashBoundManagedBinaryCannotBeUpdatedOrStopped(@TempDir Path dir) throws IOException {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        var original = new ApiBinaryUpdateService.DownloadedRelease("v1", "1.0.0", previous, null,
                java.util.List.of("tuneweave-http-v1"));
        assertTrue(service.recordManagedInstallation(dir, original, previous.getFileName().toString()));
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        var staged = stageFor(previous, dir);
        long generation = fixture.manager.managedProcessSnapshot().generation();
        Files.writeString(previous, "user-replacement-sentinel");
        assertEquals(ApiBinaryUpdateService.CheckStatus.UNMANAGED, service.inspectManagedUpdate(
                previous.toString(), "http://localhost:3000", manifest("2.0.0", "v2")).status());
        assertEquals(ApiBinaryUpdateService.ActivationStatus.FAILED, service.activateAndRestartVerified(staged,
                fixture.config, () -> "http://localhost:3000", fixture.manager,
                url -> java.util.Optional.of("2.0.0"), millis -> {}));
        assertEquals(generation, fixture.manager.managedProcessSnapshot().generation());
        assertTrue(fixture.manager.managedProcessSnapshot().owned());
        assertEquals("user-replacement-sentinel", Files.readString(previous));
        assertTrue(Files.exists(staged.binary()));
        assertEquals(previous.toString(), fixture.configuredPath);
    }

    @Test
    void stoppedManagerCannotChangeSelectionWhenAnExternalProcessOwnsItsPort(@TempDir Path dir) throws Exception {
        var fixture = new ApiServerManagerTest.Fixture(dir);
        Path previous = Path.of(fixture.configuredPath);
        service.recordManagedInstallation(dir, "v1", "1.0.0", previous.getFileName().toString());
        var staged = stageFor(previous, dir);
        try (var external = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
            fixture.port = external.getLocalPort();
            String url = "http://127.0.0.1:" + fixture.port;
            assertEquals(ApiBinaryUpdateService.ActivationStatus.FAILED, service.activateAndRestartVerified(
                    staged, fixture.config, () -> url, fixture.manager,
                    ignored -> java.util.Optional.of("2.0.0"), millis -> {}));
            assertEquals(previous.toString(), fixture.configuredPath);
            assertEquals(0, fixture.launchCalls);
            assertEquals("test executable placeholder", Files.readString(previous));
            assertTrue(Files.exists(staged.binary()));
            assertNull(service.findInstalledVersion(dir, "v2"));
            try (var probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress("127.0.0.1", fixture.port), 1000);
                external.setSoTimeout(1000);
                try (var accepted = external.accept()) {
                    assertTrue(accepted.isConnected(), "The external listener must remain untouched");
                }
            }
        }
    }

    @Test
    void obsoleteHashBoundPathReplacedByUserIsNeverDeleted(@TempDir Path dir) throws IOException {
        Path obsolete = Files.writeString(dir.resolve("old"), "old-managed-download");
        var release = new ApiBinaryUpdateService.DownloadedRelease("v1", "1.0.0", obsolete, null,
                java.util.List.of("tuneweave-http-v1"));
        assertTrue(service.recordManagedInstallation(dir, release, "old"));
        Path active = Files.writeString(dir.resolve("new"), "new-active");
        service.recordManagedInstallation(dir, "v2", "2.0.0", "new");
        Files.writeString(obsolete, "unrelated-user-sentinel");
        var report = service.cleanupObsoleteManagedBinaries(dir, active);
        assertEquals(1, report.rejected());
        assertEquals(0, report.deleted());
        assertEquals("unrelated-user-sentinel", Files.readString(obsolete));
        assertEquals("new-active", Files.readString(active));
        assertEquals("1.0.0", service.findInstalledVersion(dir, "v1"));
    }

    private static ApiServerFetcher.TuneWeaveManifest manifest(String version, String tag) {
        return indi.mopelotus.musichud.utils.JsonUtil.gson.fromJson(
                "{\"version\":\"" + version + "\",\"tag\":\"" + tag + "\"}", ApiServerFetcher.TuneWeaveManifest.class);
    }

    private static indi.mopelotus.musichud.interfaces.ServerConfig config(Path path) {
        final String[] selection = {path.toString()};
        return (indi.mopelotus.musichud.interfaces.ServerConfig) java.lang.reflect.Proxy.newProxyInstance(
                ApiBinaryUpdateServiceTest.class.getClassLoader(),
                new Class<?>[]{indi.mopelotus.musichud.interfaces.ServerConfig.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPort")) return 7832;
                    if (method.getName().equals("getServerApiBinaryExecutablePath")) return selection[0];
                    if (method.getName().equals("setServerApiBinaryExecutablePath")) selection[0] = (String) args[0];
                    return null;
                });
    }

    private static final class JsonUtilHolder {
        static void mutateVersion(ApiServerFetcher.TuneWeaveManifest manifest, String value) {
            try {
                var field = manifest.getClass().getDeclaredField("version");
                field.setAccessible(true);
                field.set(manifest, value);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }

    @Test
    void updateCheckUsesSharedSemverAndNeverOffersDowngrades(@TempDir Path dir) throws IOException {
        String[][] versions = {
            {"1.0.0-beta.2", "1.0.0-beta.1", "CURRENT"},
            {"1.0.0", "1.0.0-beta.10", "CURRENT"},
            {"1.0.0-beta.9", "1.0.0-beta.10", "AVAILABLE"},
            {"1.0.0-beta.10", "1.0.0-beta.9", "CURRENT"},
            {"1.0.0", "1.0.1", "AVAILABLE"},
            {"1.9.9", "2.0.0", "AVAILABLE"},
            {"1.9.9", "1.10.0", "AVAILABLE"},
            {"2.0.0", "1.99.99", "CURRENT"},
            {"development", "1.0.1", "UNKNOWN"},
            {"1.0.0", "development", "UNKNOWN"}
        };
        for (int index = 0; index < versions.length; index++) {
            String[] entry = versions[index];
            Path binary = Files.writeString(dir.resolve("service-" + index), "binary");
            assertTrue(service.recordManagedInstallation(dir, "installed-" + index, entry[0], binary.getFileName().toString()));
            assertEquals(ApiBinaryUpdateService.CheckStatus.valueOf(entry[2]), service.inspectManagedUpdate(
                    binary.toString(), "http://localhost:7832", manifest(entry[1], "v" + entry[1])).status(), entry[0] + " -> " + entry[1]);
        }
    }

}
