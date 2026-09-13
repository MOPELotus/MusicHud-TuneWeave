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
}
