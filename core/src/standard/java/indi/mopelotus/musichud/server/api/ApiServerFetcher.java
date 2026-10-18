package indi.mopelotus.musichud.server.api;

import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.utils.JsonUtil;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Downloads the current TuneWeave release selected by its published manifest. */
public class ApiServerFetcher {
    // Largest official beta.1 binary is 23,358,600 bytes; leave ample headroom without unbounded disk writes.
    static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;
    public static final String TUNEWEAVE_MANIFEST_URL = "https://raw.githubusercontent.com/MOPELotus/TuneWeave/main/release-manifest.json";

    // ---- data models ----

    /** Release metadata displayed by the download UI. */
    public record ReleaseSummary(String tag, String title, String htmlUrl, String publishedAt) {}

    @Getter
    public static class TuneWeaveManifest {
        @SerializedName("schema_version")
        private int schemaVersion;
        private String project;
        @SerializedName("api_version")
        private String apiVersion;
        @SerializedName("compatibility_profiles")
        private List<String> compatibilityProfiles;
        private String version;
        private String tag;
        @SerializedName("release_page")
        private String releasePage;
        private List<TuneWeaveArtifact> artifacts = List.of();
    }

    @Getter
    public static class TuneWeaveArtifact {
        private String platform;
        private String architecture;
        private String target;
        private String file;
        private String executable;
        @SerializedName("download_url")
        private String downloadUrl;
        private Verification verification;
    }

    @Getter
    public static class Verification {
        private String algorithm;
        @SerializedName("checksum_url")
        private String checksumUrl;
    }

    public enum DownloadProxy {
        AUTO(null),
        DIRECT(null),
        CLOUDFLARE_IPV4("https://gh-proxy.org/"),
        CLOUDFLARE_CN_IPV4("https://v4.gh-proxy.org/"),
        CLOUDFLARE_CN_DUAL("https://v6.gh-proxy.org/"),
        FASTLY_IPV4("https://cdn.gh-proxy.org/"),
        AXIS_NOW_IPV4("https://axisnow.gh-proxy.org/");

        private final String proxyBase;

        DownloadProxy(String proxyBase) {
            this.proxyBase = proxyBase;
        }

        public String resolveUrl(String originalUrl) {
            if (this == AUTO) throw new IllegalStateException("Automatic download source must be measured first");
            if (proxyBase == null) return originalUrl;
            return proxyBase + originalUrl;
        }
    }

    public static CompletableFuture<TuneWeaveManifest> fetchTuneWeaveManifest() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TuneWeaveManifest manifest = DownloadSources.withSource(TUNEWEAVE_MANIFEST_URL,
                        DownloadProxy.DIRECT, "application/json", null, selected -> {
                            var response = DownloadSources.open(TUNEWEAVE_MANIFEST_URL, selected, "application/json", null);
                            try (var input = response.body()) {
                                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                                if (bytes.length > 1024 * 1024) throw new IOException("TuneWeave manifest is too large");
                                return parseManifest(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        });
                if (manifest == null || manifest.getArtifacts() == null || manifest.getArtifacts().isEmpty()) {
                    throw new IOException("TuneWeave release manifest contains no artifacts");
                }
                return manifest;
            } catch (Exception error) {
                throw new RuntimeException("Failed to fetch TuneWeave release manifest", error);
            }
        }, MusicHud.EXECUTOR);
    }

    static TuneWeaveManifest parseManifest(String json) throws IOException {
        try {
            var document = JsonParser.parseString(json).getAsJsonObject();
            if (document.has("compatibility_profiles")) {
                var value = document.get("compatibility_profiles");
                if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > 16)
                    throw new IOException("Invalid TuneWeave compatibility profiles");
                var unique = new java.util.HashSet<String>();
                for (var profile : value.getAsJsonArray()) {
                    if (!profile.isJsonPrimitive() || !profile.getAsJsonPrimitive().isString()
                            || !profile.getAsString().matches("[a-z0-9][a-z0-9._-]{0,63}")
                            || !unique.add(profile.getAsString())) throw new IOException("Invalid TuneWeave compatibility profiles");
                }
            }
            TuneWeaveManifest manifest = JsonUtil.gson.fromJson(document, TuneWeaveManifest.class);
            if (manifest == null || manifest.schemaVersion != 1 || !"TuneWeave".equals(manifest.project)
                    || manifest.apiVersion == null || !manifest.apiVersion.matches("v[1-9][0-9]*")
                    || manifest.artifacts == null || manifest.artifacts.isEmpty())
                throw new IOException("Invalid TuneWeave release manifest identity");
            return manifest;
        } catch (RuntimeException invalid) { throw new IOException("Invalid TuneWeave release manifest", invalid); }
    }

    public static TuneWeaveArtifact currentTuneWeaveArtifact(TuneWeaveManifest manifest) {
        if (manifest == null || manifest.getArtifacts() == null) {
            throw new IllegalArgumentException("TuneWeave release manifest is missing artifacts");
        }
        String platform = currentPlatformName();
        String architecture = currentArchitecture();
        TuneWeaveArtifact artifact = manifest.getArtifacts().stream()
                .filter(candidate -> platform.equalsIgnoreCase(candidate.getPlatform())
                        && architecture.equalsIgnoreCase(candidate.getArchitecture()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TuneWeave has no artifact for " + platform + '/' + architecture));
        if (artifact.getFile() == null || artifact.getFile().isBlank()
                || artifact.getDownloadUrl() == null || artifact.getDownloadUrl().isBlank()
                || artifact.getVerification() == null
                || !"sha256".equalsIgnoreCase(artifact.getVerification().getAlgorithm())
                || artifact.getVerification().getChecksumUrl() == null
                || artifact.getVerification().getChecksumUrl().isBlank()) {
            throw new IllegalStateException("TuneWeave artifact metadata is incomplete for "
                    + platform + '/' + architecture);
        }
        return artifact;
    }

    public static CompletableFuture<Void> downloadTuneWeaveArtifact(
            TuneWeaveArtifact artifact, Path target, DownloadProxy proxy,
            BiConsumer<Long, Long> progress, AtomicBoolean cancelled) {
        return CompletableFuture.runAsync(() -> {
            Path temporary = null;
            List<Path> completedAttempts = new ArrayList<>();
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                // Freeze verification data over official TLS before contacting any mirror.
                String expected = fetchExpectedChecksum(artifact.getVerification().getChecksumUrl(), cancelled);
                temporary = DownloadSources.withSource(artifact.getDownloadUrl(), proxy, cancelled, selected -> {
                    var response = DownloadSources.open(artifact.getDownloadUrl(), selected,
                            "application/octet-stream", cancelled);
                    long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    Path staged = stageVerifiedArtifact(response.body(), parent, total, MAX_ARTIFACT_BYTES,
                            expected, progress, cancelled);
                    completedAttempts.add(staged);
                    return staged;
                });
                DownloadSources.checkCancelled(cancelled);
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!"windows".equalsIgnoreCase(artifact.getPlatform())) target.toFile().setExecutable(true);
            } catch (Exception error) {
                try { if (temporary != null) Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                if (error instanceof CancellationException cancellation) throw cancellation;
                throw new RuntimeException("Failed to download TuneWeave artifact", error);
            } finally {
                // withSource may observe cancellation after staging succeeds but before assigning its result.
                for (Path completed : completedAttempts) {
                    try { Files.deleteIfExists(completed); } catch (IOException ignored) { }
                }
            }
        }, MusicHud.EXECUTOR);
    }

    /** Owns only its unique temporary file; failures leave both targets and previous attempts untouched. */
    static Path stageVerifiedArtifact(InputStream input, Path directory, long declaredSize, long limit,
                                      String expected, BiConsumer<Long, Long> progress, AtomicBoolean cancelled)
            throws IOException {
        Path temporary = null;
        try {
            try (InputStream owned = input) {
                DownloadSources.checkCancelled(cancelled);
                if (limit < 1 || limit > MAX_ARTIFACT_BYTES || declaredSize < -1 || declaredSize > limit)
                    throw new IOException("TuneWeave artifact exceeds download size limit");
                temporary = Files.createTempFile(directory, ".musichud-tuneweave-", ".part");
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                long downloaded = 0;
                try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = owned.read(buffer, 0, (int) Math.min(buffer.length, limit - downloaded + 1))) != -1) {
                        DownloadSources.checkCancelled(cancelled);
                        if (count > limit - downloaded) throw new IOException("TuneWeave artifact exceeds download size limit");
                        downloaded += count;
                        if (declaredSize >= 0 && downloaded > declaredSize) throw new IOException("Invalid TuneWeave artifact length");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        if (progress != null) progress.accept(downloaded, declaredSize);
                    }
                }
                DownloadSources.checkCancelled(cancelled);
                if (declaredSize >= 0 && downloaded != declaredSize) throw new IOException("Incomplete TuneWeave artifact");
                if (!HexFormat.of().formatHex(digest.digest()).equals(expected))
                    throw new IOException("TuneWeave SHA-256 verification failed");
            }
            return temporary;
        } catch (IOException | RuntimeException error) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    private static String fetchExpectedChecksum(String original, AtomicBoolean cancelled) throws IOException, InterruptedException {
        var response = DownloadSources.open(original, DownloadProxy.DIRECT, "text/plain", cancelled);
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Checksum response is too large");
            return parseChecksum(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static String parseChecksum(String text) throws IOException {
        String expected = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid official SHA-256 checksum");
        return expected;
    }

    private static String currentPlatformName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        if (os.contains("linux")) return "linux";
        return os;
    }

    private static String currentArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.equals("x64")) return "x86_64";
        return arch;
    }
}
