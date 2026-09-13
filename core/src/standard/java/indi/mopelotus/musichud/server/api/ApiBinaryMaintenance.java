package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.MusicHud;
import java.nio.file.Path;

/** Maintenance for installations owned by the standard edition's updater. */
final class ApiBinaryMaintenance {
    private ApiBinaryMaintenance() {}

    static void afterStartup(Path executable) {
        ApiBinaryUpdateService.CleanupReport report = ApiBinaryUpdateService.getInstance()
                .cleanupObsoleteManagedBinaries(executable.toAbsolutePath().getParent(), executable);
        if (report.failed() > 0 || report.rejected() > 0) {
            MusicHud.getLogger(ApiBinaryMaintenance.class).warn(
                    "Deferred cleanup of {} TuneWeave binaries; rejected {} unsafe manifest entries",
                    report.failed(), report.rejected());
        }
    }
}
