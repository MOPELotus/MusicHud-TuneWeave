package indi.mopelotus.musichud.server.api;

import com.google.gson.reflect.TypeToken;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.utils.JsonUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiBinaryUpdateService {
    static final String INSTALLATIONS_FILE_NAME = "tuneweave-installations.json";
    private static final String LEGACY_INSTALLATIONS_FILE_NAME = "mh-api.json";
    private static final ApiBinaryUpdateService INSTANCE = new ApiBinaryUpdateService();
    private static final Logger LOGGER = MusicHud.getLogger(ApiBinaryUpdateService.class);

    public static ApiBinaryUpdateService getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<ApiServerFetcher.ReleaseSummary> fetchLatestRelease() {
        return ApiServerFetcher.fetchTuneWeaveManifest().thenApply(manifest ->
                new ApiServerFetcher.ReleaseSummary(
                        manifest.getTag(), "TuneWeave " + manifest.getVersion(),
                        manifest.getReleasePage(), null));
    }

    public CompletableFuture<DownloadedRelease> downloadToTemp(
            Path targetDir, ApiServerFetcher.DownloadProxy proxy,
            BiConsumer<Long, Long> progress, AtomicBoolean cancelled) {
        return ApiServerFetcher.fetchTuneWeaveManifest().thenCompose(manifest -> {
            if (manifest.getTag() == null || manifest.getTag().isBlank()
                    || manifest.getVersion() == null || manifest.getVersion().isBlank()) {
                throw new IllegalStateException("TuneWeave release manifest is missing version metadata");
            }
            ApiServerFetcher.TuneWeaveArtifact artifact = ApiServerFetcher.currentTuneWeaveArtifact(manifest);
            String tempFileName = artifact.getFile() + "." + manifest.getTag() + ".temp";
            Path tempFile = targetDir.resolve(tempFileName);
            return ApiServerFetcher.downloadTuneWeaveArtifact(artifact, tempFile, proxy, progress, cancelled)
                    .thenApply(v -> new DownloadedRelease(manifest.getTag(), manifest.getVersion(), tempFile));
        });
    }

    public Path resolveFinalPath(Path tempFile, String releaseTag) {
        if (tempFile == null || !Files.exists(tempFile)) return null;
        String tempName = tempFile.getFileName().toString();
        String marker = "." + releaseTag + ".temp";
        String baseName = tempName.endsWith(marker)
                ? tempName.substring(0, tempName.length() - marker.length()) : tempName;
        Path targetDir = tempFile.toAbsolutePath().getParent();
        Path namedFile = targetDir.resolve(baseName);

        // proactively stop server if target file is the running executable
        String currentPath = ServerConfig.getInstance().getServerApiBinaryExecutablePath();
        if (namedFile.toAbsolutePath().normalize().toString()
                .equals(Paths.get(currentPath).toAbsolutePath().normalize().toString())
                && ApiServerManager.getInstance().getBinaryApiServerStatus() == ApiServerManager.BinaryApiServerStatus.RUNNING) {
            ApiServerManager.getInstance().stopApiServer();
            for (int retry = 0; retry < 20; retry++) {
                try {
                    Thread.sleep(150);
                    Files.move(tempFile, namedFile, StandardCopyOption.REPLACE_EXISTING);
                    return namedFile;
                } catch (Exception ignored) {}
            }
        }

        try {
            Files.move(tempFile, namedFile, StandardCopyOption.REPLACE_EXISTING);
            return namedFile;
        } catch (IOException ignored) {}

        // fallback: append .n before extension
        String bn = baseName;
        String ext = "";
        int dotIdx = bn.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = bn.substring(dotIdx);
            bn = bn.substring(0, dotIdx);
        }
        for (int n = 1; n < 100; n++) {
            Path numberedFile = targetDir.resolve(bn + "." + n + ext);
            try {
                Files.move(tempFile, numberedFile);
                return numberedFile;
            } catch (IOException ignored) {}
        }
        return null;
    }

    public String relativizePath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (abs.startsWith(cwd)) {
            return cwd.relativize(abs).toString();
        }
        return abs.toString();
    }

    public record ReleaseMeta(String version, String file) {}

    public record DownloadedRelease(String tag, String version, Path tempFile) {}

    public boolean recordManagedInstallation(Path targetDir, String releaseTag, String version, String fileName) {
        if (releaseTag == null || releaseTag.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("TuneWeave release metadata is incomplete");
        }
        Path managedFile = resolveManagedFile(targetDir, fileName);
        if (managedFile == null || !Files.isRegularFile(managedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Managed TuneWeave binary must be a regular file directly inside its install directory");
        }
        Map<String, ReleaseMeta> installations = readInstallations(targetDir);
        installations.values().removeIf(meta -> meta != null && fileName.equals(meta.file()));
        installations.put(releaseTag, new ReleaseMeta(version, fileName));
        return writeInstallations(targetDir, installations);
    }

    public String findInstalledVersion(Path targetDir, String releaseTag) {
        ReleaseMeta release = readInstallations(targetDir).get(releaseTag);
        return release == null ? null : release.version();
    }

    /**
     * Deletes obsolete binaries that are explicitly listed in this project's installation manifest.
     * The active binary must exist directly inside {@code targetDir} and be present in the manifest;
     * otherwise cleanup is skipped.
     */
    public CleanupReport cleanupObsoleteManagedBinaries(Path targetDir, Path activeBinary) {
        return cleanupObsoleteManagedBinaries(targetDir, activeBinary, Files::deleteIfExists);
    }

    CleanupReport cleanupObsoleteManagedBinaries(
            Path targetDir, Path activeBinary, ManagedFileDeleter deleter) {
        Path installDir = targetDir.toAbsolutePath().normalize();
        Path active = activeBinary.toAbsolutePath().normalize();
        if (!installDir.equals(active.getParent())
                || !Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
            return CleanupReport.skippedCleanup();
        }

        Map<String, ReleaseMeta> installations = readInstallations(installDir);
        boolean activeIsManaged = installations.values().stream()
                .map(release -> release == null ? null : resolveManagedFile(installDir, release.file()))
                .anyMatch(active::equals);
        if (!activeIsManaged) {
            return CleanupReport.skippedCleanup();
        }
        int deleted = 0;
        int missing = 0;
        int failed = 0;
        int rejected = 0;
        boolean changed = false;

        Iterator<Map.Entry<String, ReleaseMeta>> iterator = installations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ReleaseMeta> entry = iterator.next();
            ReleaseMeta release = entry.getValue();
            Path managedFile = release == null ? null : resolveManagedFile(installDir, release.file());
            if (managedFile == null) {
                rejected++;
                continue;
            }
            if (managedFile.equals(active)) {
                continue;
            }
            if (!Files.exists(managedFile, LinkOption.NOFOLLOW_LINKS)) {
                iterator.remove();
                missing++;
                changed = true;
                continue;
            }
            if (!Files.isRegularFile(managedFile, LinkOption.NOFOLLOW_LINKS)) {
                rejected++;
                continue;
            }
            try {
                if (deleter.delete(managedFile)) {
                    iterator.remove();
                    deleted++;
                    changed = true;
                } else {
                    failed++;
                }
            } catch (IOException | SecurityException error) {
                failed++;
                LOGGER.warn("Could not delete obsolete managed TuneWeave binary {}; will retry later",
                        managedFile, error);
            }
        }

        if (changed) {
            writeInstallations(installDir, installations);
        }
        return new CleanupReport(false, deleted, missing, failed, rejected);
    }

    private Map<String, ReleaseMeta> readInstallations(Path targetDir) {
        Path manifest = targetDir.resolve(INSTALLATIONS_FILE_NAME);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            manifest = targetDir.resolve(LEGACY_INSTALLATIONS_FILE_NAME);
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return new HashMap<>();
        }
        try {
            String content = Files.readString(manifest);
            Map<String, ReleaseMeta> installations = JsonUtil.gson.fromJson(content,
                    new TypeToken<Map<String, ReleaseMeta>>() { }.getType());
            return installations == null ? new HashMap<>() : new HashMap<>(installations);
        } catch (Exception error) {
            LOGGER.warn("Could not read TuneWeave managed installation manifest {}", manifest, error);
            return new HashMap<>();
        }
    }

    private boolean writeInstallations(Path targetDir, Map<String, ReleaseMeta> installations) {
        Path manifest = targetDir.resolve(INSTALLATIONS_FILE_NAME);
        Path temporary = targetDir.resolve(INSTALLATIONS_FILE_NAME + ".tmp");
        try {
            Files.createDirectories(targetDir);
            Files.writeString(temporary, JsonUtil.gson.toJson(installations),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            LOGGER.warn("Could not update TuneWeave managed installation manifest {}", manifest, error);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private static Path resolveManagedFile(Path targetDir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        try {
            Path relative = Path.of(fileName);
            if (relative.isAbsolute() || relative.getNameCount() != 1) {
                return null;
            }
            Path installDir = targetDir.toAbsolutePath().normalize();
            Path resolved = installDir.resolve(relative).normalize();
            return installDir.equals(resolved.getParent()) ? resolved : null;
        } catch (InvalidPathException error) {
            return null;
        }
    }

    @FunctionalInterface
    interface ManagedFileDeleter {
        boolean delete(Path path) throws IOException;
    }

    public record CleanupReport(
            boolean skipped, int deleted, int missing, int failed, int rejected) {
        private static CleanupReport skippedCleanup() {
            return new CleanupReport(true, 0, 0, 0, 0);
        }
    }
}
