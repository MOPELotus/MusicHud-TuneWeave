package indi.mopelotus.musichud.server.api;

import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.utils.JsonUtil;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Downloads the current TuneWeave release selected by its published manifest. */
public class ApiServerFetcher {
    public static final String TUNEWEAVE_MANIFEST_URL = "https://raw.githubusercontent.com/MOPELotus/TuneWeave/main/release-manifest.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(MusicHud.EXECUTOR)
            .build();

    // ---- data models ----

    /** Release metadata displayed by the download UI. */
    public record ReleaseSummary(String tag, String title, String htmlUrl, String publishedAt) {}

    @Getter
    public static class TuneWeaveManifest {
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
        DIRECT(null),
        CLOUDFLARE_IPV4("https://gh-proxy.org/"),
        CLOUDFLARE_CN_IPV4("https://v4.gh-proxy.org/"),
        CLOUDFLARE_CN_DUAL("https://v6.gh-proxy.org/"),
        FASTLY_IPV4("https://cdn.gh-proxy.org/");

        private final String proxyBase;

        DownloadProxy(String proxyBase) {
            this.proxyBase = proxyBase;
        }

        public String resolveUrl(String originalUrl) {
            if (proxyBase == null) return originalUrl;
            return proxyBase + originalUrl;
        }
    }

    public static CompletableFuture<TuneWeaveManifest> fetchTuneWeaveManifest() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(TUNEWEAVE_MANIFEST_URL))
                        .header("Accept", "application/json")
                        .header("User-Agent", MusicHud.PROJECT_NAME)
                        .GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("TuneWeave release manifest returned HTTP " + response.statusCode());
                }
                TuneWeaveManifest manifest = JsonUtil.gson.fromJson(response.body(), TuneWeaveManifest.class);
                if (manifest == null || manifest.getArtifacts() == null || manifest.getArtifacts().isEmpty()) {
                    throw new IOException("TuneWeave release manifest contains no artifacts");
                }
                return manifest;
            } catch (Exception error) {
                throw new RuntimeException("Failed to fetch TuneWeave release manifest", error);
            }
        }, MusicHud.EXECUTOR);
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
            Path temporary = target.resolveSibling(target.getFileName() + ".part");
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String url = proxy.resolveUrl(artifact.getDownloadUrl());
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", MusicHud.PROJECT_NAME)
                        .GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IOException("TuneWeave download returned HTTP " + response.statusCode());
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                long downloaded = 0;
                try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(
                        temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled != null && cancelled.get()) throw new CancellationException("Download cancelled");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        downloaded += count;
                        if (progress != null) progress.accept(downloaded, total);
                    }
                }
                HttpRequest checksumRequest = HttpRequest.newBuilder(URI.create(
                                proxy.resolveUrl(artifact.getVerification().getChecksumUrl())))
                        .header("User-Agent", MusicHud.PROJECT_NAME).GET().build();
                HttpResponse<String> checksumResponse = HTTP.send(checksumRequest,
                        HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                if (checksumResponse.statusCode() != 200) throw new IOException("Checksum download returned HTTP " + checksumResponse.statusCode());
                String expected = checksumResponse.body().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(expected)) throw new IOException("TuneWeave SHA-256 verification failed");
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!"windows".equalsIgnoreCase(artifact.getPlatform())) target.toFile().setExecutable(true);
            } catch (Exception error) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                if (error instanceof CancellationException cancellation) throw cancellation;
                throw new RuntimeException("Failed to download TuneWeave artifact", error);
            }
        }, MusicHud.EXECUTOR);
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
