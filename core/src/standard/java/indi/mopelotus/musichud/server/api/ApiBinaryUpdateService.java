package indi.mopelotus.musichud.server.api;

import com.google.gson.reflect.TypeToken;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.utils.JsonUtil;
import indi.mopelotus.musichud.utils.ReleaseVersion;
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
    private final java.util.Set<Path> rollbackProtected = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        return ApiServerFetcher.fetchTuneWeaveManifest().thenCompose(manifest ->
                downloadToTemp(manifest, targetDir, proxy, progress, cancelled));
    }

    public CompletableFuture<DownloadedRelease> downloadToTemp(
            ApiServerFetcher.TuneWeaveManifest manifest, Path targetDir,
            ApiServerFetcher.DownloadProxy proxy, BiConsumer<Long, Long> progress, AtomicBoolean cancelled) {
        if (manifest.getTag() == null || manifest.getTag().isBlank()
                || manifest.getVersion() == null || manifest.getVersion().isBlank()) {
            throw new IllegalStateException("TuneWeave release manifest is missing version metadata");
        }
        TuneWeaveCompatibility.requireCompatible(manifest);
        String releaseTag = manifest.getTag();
        String releaseVersion = manifest.getVersion();
        java.util.List<String> releaseProfiles = snapshotProfiles(manifest.getCompatibilityProfiles());
        ApiServerFetcher.TuneWeaveArtifact artifact = ApiServerFetcher.currentTuneWeaveArtifact(manifest);
        String tempFileName = "tuneweave-" + java.util.UUID.randomUUID()
                + ("windows".equalsIgnoreCase(artifact.getPlatform()) ? ".exe" : "")
                + "." + safeTag(releaseTag) + ".temp";
        Path tempFile = targetDir.resolve(tempFileName);
        return ApiServerFetcher.downloadTuneWeaveArtifact(artifact, tempFile, proxy, progress, cancelled)
                .thenApply(v -> {
                    try {
                        var signer = "windows".equalsIgnoreCase(artifact.getPlatform())
                                ? SodaSignerInstaller.download(targetDir, proxy, progress, cancelled) : null;
                        SodaSignerInstaller.checkCancelled(cancelled);
                        return new DownloadedRelease(releaseTag, releaseVersion, tempFile, signer, releaseProfiles);
                    } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Download cancelled"); }
                });
    }

    private static String safeTag(String tag) {
        if (tag == null || !tag.matches("[A-Za-z0-9._+-]{1,128}"))
            throw new IllegalArgumentException("Invalid TuneWeave release tag");
        return tag;
    }

    /** A discovery result is not a compatibility approval; the client release policy owns that decision. */
    public record UpdateOffer(Path previousBinary, String currentVersion, String targetVersion,
                              String tag, String manifestJson) {
        public ApiServerFetcher.TuneWeaveManifest manifest() {
            return JsonUtil.gson.fromJson(manifestJson, ApiServerFetcher.TuneWeaveManifest.class);
        }
    }

    public enum CheckStatus { AVAILABLE, CURRENT, UNMANAGED, UNKNOWN }
    public record UpdateCheck(CheckStatus status, UpdateOffer offer) {}
    public record StagedUpdate(UpdateOffer offer, Path binary, String sha256) {}

    public CompletableFuture<UpdateCheck> checkManagedUpdate(String configuredBinary, String serviceUrl) {
        if (!isLocalServiceUrl(serviceUrl) || managedVersion(configuredBinary) == null)
            return CompletableFuture.completedFuture(new UpdateCheck(CheckStatus.UNMANAGED, null));
        return ApiServerFetcher.fetchTuneWeaveManifest()
                .thenApply(manifest -> inspectManagedUpdate(configuredBinary, serviceUrl, manifest));
    }

    public UpdateCheck inspectManagedUpdate(String configuredBinary, String serviceUrl,
                                            ApiServerFetcher.TuneWeaveManifest manifest) {
        String current = isLocalServiceUrl(serviceUrl) ? managedVersion(configuredBinary) : null;
        if (current == null) return new UpdateCheck(CheckStatus.UNMANAGED, null);
        if (manifest == null || manifest.getVersion() == null || manifest.getVersion().isBlank())
            throw new IllegalArgumentException("TuneWeave release version is missing");
        safeTag(manifest.getTag());
        // Serialize now: later manifest refreshes/caller mutation cannot change an accepted target.
        UpdateOffer offer = new UpdateOffer(Path.of(configuredBinary).toAbsolutePath().normalize(),
                current, manifest.getVersion(), manifest.getTag(), JsonUtil.gson.toJson(manifest));
        try {
            var installed = ReleaseVersion.parse(current);
            var target = ReleaseVersion.parse(manifest.getVersion());
            if (installed.cfRevision() != 0 || target.cfRevision() != 0) return new UpdateCheck(CheckStatus.UNKNOWN, offer);
            return new UpdateCheck(target.compareTo(installed) > 0 ? CheckStatus.AVAILABLE : CheckStatus.CURRENT, offer);
        } catch (IllegalArgumentException unknownVersion) { return new UpdateCheck(CheckStatus.UNKNOWN, offer); }
    }

    /** Downloads exactly the checked target. The caller must approve compatibility before calling. */
    public CompletableFuture<StagedUpdate> stageUpdate(UpdateOffer offer, ApiServerFetcher.DownloadProxy proxy,
            BiConsumer<Long, Long> progress, AtomicBoolean cancelled) {
        validateOffer(offer);
        return downloadToTemp(offer.manifest(), offer.previousBinary().getParent(), proxy, progress, cancelled)
                .thenApply(release -> stageDownloaded(offer, release));
    }

    public StagedUpdate stageDownloaded(UpdateOffer offer, DownloadedRelease release) {
        validateOffer(offer);
        if (!offer.tag().equals(release.tag()) || !offer.targetVersion().equals(release.version())
                || !offer.previousBinary().getParent().equals(release.tempFile().toAbsolutePath().normalize().getParent()))
            throw new IllegalArgumentException("Downloaded TuneWeave release does not match the update offer");
        Path target = resolveFinalPath(release);
        if (target == null) throw new IllegalStateException("Could not stage TuneWeave update");
        try {
            return new StagedUpdate(offer, target, sha256(target));
        } catch (IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    /**
     * Changes only the next-start selection, retaining the running/previous executable for rollback.
     * The caller owns restarting the managed process, and may defer this until a joint mod update starts.
     */
    public synchronized boolean activateStaged(StagedUpdate staged, ServerConfig config, String actualServiceUrl) {
        if (staged == null || config == null || !usesManagedEndpoint(config, actualServiceUrl)) return false;
        UpdateOffer offer = staged.offer();
        try {
            validateOffer(offer);
            Path configured = Path.of(config.getServerApiBinaryExecutablePath()).toAbsolutePath().normalize();
            Path binary = staged.binary().toAbsolutePath().normalize();
            if (!configured.equals(offer.previousBinary()) || binary.equals(configured)
                    || !configured.getParent().equals(binary.getParent())
                    || !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(binary).equals(staged.sha256())) return false;
            if (!recordInstallation(binary.getParent(), offer.tag(), offer.targetVersion(), binary.getFileName().toString(),
                    staged.sha256(), snapshotProfiles(offer.manifest().getCompatibilityProfiles())))
                return false;
            String previous = config.getServerApiBinaryExecutablePath();
            try {
                config.setServerApiBinaryExecutablePath(relativizePath(binary));
                config.save();
                return true;
            } catch (RuntimeException failure) {
                config.setServerApiBinaryExecutablePath(previous);
                throw failure;
            }
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    public enum ActivationStatus { ACTIVE, NEXT_START, ROLLED_BACK, FAILED, SUPERSEDED, ROLLBACK_FAILED }

    public CompletableFuture<ActivationStatus> activateAndRestartVerified(StagedUpdate staged, ServerConfig config,
            java.util.function.Supplier<String> actualServiceUrl) {
        return CompletableFuture.supplyAsync(() -> activateAndRestartVerified(staged, config, actualServiceUrl,
                ApiServerManager.getInstance(),
                ServiceUpdateHealth::serverVersionAt, Thread::sleep),
                MusicHud.EXECUTOR);
    }

    ActivationStatus activateAndRestartVerified(StagedUpdate staged, ServerConfig config,
            java.util.function.Supplier<String> actualServiceUrl, ApiServerManager manager,
            java.util.function.Function<String, java.util.Optional<String>> versionProbe,
            ApiServerManager.Sleeper sleeper) {
        return activateAndRestartVerified(staged, config, actualServiceUrl, manager,
                (url, budget) -> versionProbe.apply(url), sleeper);
    }

    ActivationStatus activateAndRestartVerified(StagedUpdate staged, ServerConfig config,
            java.util.function.Supplier<String> actualServiceUrl, ApiServerManager manager,
            java.util.function.BiFunction<String, java.time.Duration, java.util.Optional<String>> versionProbe,
            ApiServerManager.Sleeper sleeper) {
        if (manager == null || staged == null) return ActivationStatus.FAILED;
        final String expectedUrl = actualServiceUrl.get();
        if (!usesManagedEndpoint(config, expectedUrl)) return ActivationStatus.FAILED;
        var before = manager.managedProcessSnapshot();
        Path previous = staged.offer().previousBinary();
        boolean running = before.owned() && previous.equals(before.executable())
                && before.status() == ApiServerManager.BinaryApiServerStatus.RUNNING;
        if (!running && (before.processAlive() || before.status() != ApiServerManager.BinaryApiServerStatus.STOPPED))
            return ActivationStatus.FAILED;
        if (!rollbackProtected.add(previous)) return ActivationStatus.FAILED;
        try {
            if (!expectedUrl.equals(actualServiceUrl.get())) return ActivationStatus.SUPERSEDED;
            if (!running) {
                synchronized (manager) {
                    if (manager.managedProcessSnapshot().generation() != before.generation())
                        return ActivationStatus.SUPERSEDED;
                    // STOPPED describes this manager, not arbitrary listeners on its configured local port.
                    if (!ServiceUpdateHealth.localPortAvailableForStart(expectedUrl)) return ActivationStatus.FAILED;
                    if (!expectedUrl.equals(actualServiceUrl.get()) || !usesManagedEndpoint(config, expectedUrl))
                        return ActivationStatus.SUPERSEDED;
                    return activateStaged(staged, config, expectedUrl) ? ActivationStatus.NEXT_START : ActivationStatus.FAILED;
                }
            }
            long updatedGeneration = manager.restartIfGeneration(before.generation(), () -> {
                if (!expectedUrl.equals(actualServiceUrl.get()) || !activateStaged(staged, config, expectedUrl))
                    throw new IllegalStateException("TuneWeave update selection changed");
            });
            if (updatedGeneration < 0) return ActivationStatus.SUPERSEDED;
            boolean healthy = awaitVersion(manager, updatedGeneration, staged.binary(), staged.offer().targetVersion(),
                    config, expectedUrl, actualServiceUrl, versionProbe, sleeper);
            if (healthy) return ActivationStatus.ACTIVE;
            var failed = manager.managedProcessSnapshot();
            if (failed.generation() != updatedGeneration || !selectionMatches(config, staged.binary())
                    || !expectedUrl.equals(actualServiceUrl.get()) || !usesManagedEndpoint(config, expectedUrl))
                return ActivationStatus.SUPERSEDED;
            long rollbackGeneration = manager.restartIfGeneration(updatedGeneration, () -> {
                if (!selectionMatches(config, staged.binary()) || !expectedUrl.equals(actualServiceUrl.get())
                        || !usesManagedEndpoint(config, expectedUrl))
                    throw new IllegalStateException("TuneWeave selection changed before rollback");
                config.setServerApiBinaryExecutablePath(relativizePath(previous));
                config.save();
            });
            if (rollbackGeneration < 0) return ActivationStatus.SUPERSEDED;
            return awaitVersion(manager, rollbackGeneration, previous, staged.offer().currentVersion(), config,
                    expectedUrl, actualServiceUrl, versionProbe, sleeper)
                    ? ActivationStatus.ROLLED_BACK : ActivationStatus.ROLLBACK_FAILED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ActivationStatus.FAILED;
        } catch (RuntimeException invalid) {
            return ActivationStatus.FAILED;
        } finally {
            rollbackProtected.remove(previous);
        }
    }

    private static boolean awaitVersion(ApiServerManager manager, long generation, Path binary, String version,
            ServerConfig config, String expectedUrl, java.util.function.Supplier<String> actualServiceUrl,
            java.util.function.BiFunction<String, java.time.Duration, java.util.Optional<String>> probe, ApiServerManager.Sleeper sleeper)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (int count = 0; count < 120 && deadline - System.nanoTime() > 0; count++) {
            var current = manager.managedProcessSnapshot();
            if (current.generation() != generation || !selectionMatches(config, binary)
                    || !expectedUrl.equals(actualServiceUrl.get()) || !usesManagedEndpoint(config, expectedUrl)) return false;
            if (current.owned() && binary.equals(current.executable())
                    && current.status() == ApiServerManager.BinaryApiServerStatus.RUNNING) {
                boolean correctVersion;
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    correctVersion = probe.apply(expectedUrl, java.time.Duration.ofNanos(remaining))
                            .filter(version::equals).isPresent();
                }
                catch (RuntimeException unavailable) { correctVersion = false; }
                // Health completion is asynchronous too: reject a stale success after a user switch.
                var after = manager.managedProcessSnapshot();
                if (deadline - System.nanoTime() <= 0 || after.generation() != generation || !selectionMatches(config, binary)
                        || !expectedUrl.equals(actualServiceUrl.get()) || !usesManagedEndpoint(config, expectedUrl)) return false;
                if (correctVersion && after.owned() && binary.equals(after.executable())) return true;
            }
            long remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) return false;
            sleeper.sleep(Math.min(250, remainingMillis));
        }
        return false;
    }

    private static boolean selectionMatches(ServerConfig config, Path binary) {
        try { return binary.equals(Path.of(config.getServerApiBinaryExecutablePath()).toAbsolutePath().normalize()); }
        catch (RuntimeException invalid) { return false; }
    }

    private void validateOffer(UpdateOffer offer) {
        if (offer == null || offer.previousBinary() == null
                || !offer.currentVersion().equals(managedVersion(offer.previousBinary().toString())))
            throw new IllegalStateException("The managed TuneWeave installation changed during update");
        ApiServerFetcher.TuneWeaveManifest manifest = offer.manifest();
        if (manifest == null || !offer.tag().equals(manifest.getTag())
                || !offer.targetVersion().equals(manifest.getVersion()))
            throw new IllegalArgumentException("TuneWeave update target changed");
    }

    private String managedVersion(String configuredBinary) {
        if (configuredBinary == null || configuredBinary.isBlank()) return null;
        try {
            Path binary = Path.of(configuredBinary).toAbsolutePath().normalize();
            if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) return null;
            var matching = readInstallations(binary.getParent()).values().stream()
                    .filter(meta -> meta != null && meta.version() != null && !meta.version().isBlank()
                            && binary.equals(resolveManagedFile(binary.getParent(), meta.file()))).toList();
            boolean hashBound = matching.stream().anyMatch(meta -> meta.sha256() != null);
            String digest = hashBound ? sha256(binary) : null;
            return matching.stream().filter(meta -> !hashBound || digest.equals(meta.sha256()))
                    .map(ReleaseMeta::version).findFirst().orElse(null);
        } catch (IOException | InvalidPathException invalid) {
            return null;
        }
    }

    public static boolean usesManagedEndpoint(ServerConfig config, String value) {
        if (!isLocalServiceUrl(value)) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return port == config.getPort() && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (RuntimeException invalid) { return false; }
    }

    public static boolean isLocalServiceUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = new byte[32 * 1024];
                int count;
                while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Path resolveFinalPath(Path tempFile, String releaseTag) {
        if (tempFile == null || !Files.isRegularFile(tempFile, LinkOption.NOFOLLOW_LINKS)) return null;
        String tempName = tempFile.getFileName().toString();
        String marker = "." + safeTag(releaseTag) + ".temp";
        String baseName = tempName.endsWith(marker)
                ? tempName.substring(0, tempName.length() - marker.length()) : tempName;
        Path targetDir = tempFile.toAbsolutePath().getParent();
        Path namedFile = targetDir.resolve(baseName);

        // Install into a fresh path. Never stop or replace an executable that may be in use,
        // and never overwrite files that were not created by this installation attempt.
        try {
            Files.move(tempFile, namedFile);
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

    public record ReleaseMeta(String version, String file, String sha256, java.util.List<String> profiles) {
        public ReleaseMeta(String version, String file) { this(version, file, null, null); }
        public ReleaseMeta { if (profiles != null) profiles = java.util.List.copyOf(profiles); }
    }

    public record DownloadedRelease(String tag, String version, Path tempFile, SodaSignerSupport.Installation signer,
                                    java.util.List<String> profiles) {
        public DownloadedRelease(String tag, String version, Path tempFile) { this(tag, version, tempFile, null, java.util.List.of()); }
        public DownloadedRelease(String tag, String version, Path tempFile, SodaSignerSupport.Installation signer) {
            this(tag, version, tempFile, signer, java.util.List.of());
        }
        public DownloadedRelease { profiles = snapshotProfiles(profiles); }
    }

    private static java.util.List<String> snapshotProfiles(java.util.List<String> profiles) {
        if (profiles == null) return java.util.List.of();
        if (profiles.size() > 16) throw new IllegalArgumentException("Too many TuneWeave compatibility profiles");
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (String profile : profiles) {
            if (profile == null || !profile.matches("[a-z0-9][a-z0-9._-]{0,63}") || !unique.add(profile))
                throw new IllegalArgumentException("Invalid TuneWeave compatibility profile");
        }
        return java.util.List.copyOf(profiles);
    }

    /** Records only the immutable publisher declaration carried by the checked download. */
    public synchronized boolean recordManagedInstallation(Path targetDir, DownloadedRelease release, String fileName) {
        Path binary = resolveManagedFile(targetDir, fileName);
        if (release == null || binary == null || !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return recordInstallation(targetDir, release.tag(), release.version(), fileName, sha256(binary), release.profiles());
        } catch (IOException invalid) { return false; }
    }

    private boolean recordInstallation(Path targetDir, String tag, String version, String fileName,
                                      String digest, java.util.List<String> profiles) {
        Path binary = resolveManagedFile(targetDir, fileName);
        if (tag == null || tag.isBlank() || version == null || version.isBlank() || binary == null
                || !Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (digest == null || !digest.matches("[0-9a-f]{64}") || !digest.equals(sha256(binary))) return false;
            Map<String, ReleaseMeta> installations = readInstallations(targetDir);
            installations.values().removeIf(meta -> meta != null && fileName.equals(meta.file()));
            installations.put(tag, new ReleaseMeta(version, fileName, digest, snapshotProfiles(profiles)));
            return writeInstallations(targetDir, installations);
        } catch (IOException invalid) { return false; }
    }

    /** An installation record alone is insufficient: bind its declaration to current bytes and real health version. */
    public java.util.Set<String> installedProfiles(Path binary, String actualHealthVersion) {
        if (binary == null || actualHealthVersion == null || actualHealthVersion.isBlank()) return java.util.Set.of();
        Path normalized = binary.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) return java.util.Set.of();
        try {
            String digest = sha256(normalized);
            java.util.Set<String> verified = new java.util.HashSet<>();
            for (ReleaseMeta meta : readInstallations(normalized.getParent()).values()) {
                if (meta != null && actualHealthVersion.equals(meta.version()) && digest.equals(meta.sha256())
                        && normalized.equals(resolveManagedFile(normalized.getParent(), meta.file())))
                    verified.addAll(snapshotProfiles(meta.profiles()));
            }
            return java.util.Set.copyOf(verified);
        } catch (IOException | IllegalArgumentException invalid) { return java.util.Set.of(); }
    }

    public Path resolveFinalPath(DownloadedRelease release) {
        if (release.signer() == null) return resolveFinalPath(release.tempFile(), release.tag());
        try {
            Path temporary = release.tempFile().toAbsolutePath();
            Path directory = temporary.getParent();
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) return null;
            SodaSignerSupport.validate(directory, release.signer());
            // New main+descriptor pair: running and unrelated binaries are never overwritten.
            Path target = directory.resolve("tuneweave-" + java.util.UUID.randomUUID() + ".exe");
            SodaSignerSupport.writeNew(target, release.signer());
            Files.move(temporary, target);
            return target;
        } catch (IOException invalid) {
            LOGGER.warn("Could not install the managed TuneWeave/Soda signer pair");
            return null;
        }
    }

    public synchronized boolean recordManagedInstallation(Path targetDir, String releaseTag, String version, String fileName) {
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

    synchronized CleanupReport cleanupObsoleteManagedBinaries(
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
            if (managedFile.equals(active) || rollbackProtected.contains(managedFile)) {
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
                if (release.sha256() != null && !release.sha256().equals(sha256(managedFile))) {
                    rejected++; // The old path is now user-owned content; retain both its file and retry record.
                    continue;
                }
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
        Path temporary = null;
        try {
            Files.createDirectories(targetDir);
            temporary = Files.createTempFile(targetDir, INSTALLATIONS_FILE_NAME + ".", ".tmp");
            Files.writeString(temporary, JsonUtil.gson.toJson(installations), StandardOpenOption.TRUNCATE_EXISTING);
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
                if (temporary != null) Files.deleteIfExists(temporary);
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
