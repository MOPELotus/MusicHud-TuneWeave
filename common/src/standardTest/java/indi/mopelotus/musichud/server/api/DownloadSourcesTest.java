package indi.mopelotus.musichud.server.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static indi.mopelotus.musichud.server.api.ApiServerFetcher.DownloadProxy.*;

class DownloadSourcesTest {
    private static final String RELEASE = "https://github.com/MOPELotus/TuneWeave/releases/download/v1/file";
    @Test void trustBoundaryRejectsCredentialsPathsPortsAndOtherProjects() throws Exception {
        DownloadSources.validateOriginal(RELEASE);
        DownloadSources.validateOriginal("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/download/v1.3.1/musichud-tuneweave-fabric-1.3.1%2B26.3.jar");
        for (String bad : List.of("http://github.com/MOPELotus/TuneWeave/releases/download/v1/file",
                RELEASE + "?token=secret", RELEASE + "#x", RELEASE.replace("github.com", "github.com.evil.test"),
                RELEASE.replace("github.com", "user@github.com"), RELEASE.replace("github.com", "github.com:443"),
                RELEASE.replace("TuneWeave", "Other"), RELEASE.replace("/v1/", "/../"),
                RELEASE.replace("/v1/", "/%2e%2e/"), RELEASE + "%2fprivate", RELEASE + "%252B",
                RELEASE.replace("/v1/", "/v1%2Bbad/"), "https://127.0.0.1/file"))
            assertThrows(IOException.class, () -> DownloadSources.validateOriginal(bad), bad);
        assertThrows(IllegalStateException.class, () -> AUTO.resolveUrl(RELEASE));
    }
    @Test void autoFallbackIncludesVerificationFailureButManualDoesNotSwitch() throws Exception {
        DownloadSources.cacheRanking(RELEASE + "\napplication/octet-stream", List.of(FASTLY_IPV4, DIRECT), System.nanoTime());
        List<Object> calls = new ArrayList<>();
        String result = DownloadSources.withSource(RELEASE, AUTO, null, source -> {
            calls.add(source); if (source == FASTLY_IPV4) throw new IOException("Checksum mismatch"); return "verified";
        });
        assertEquals("verified", result); assertEquals(List.of(FASTLY_IPV4, DIRECT), calls);
        calls.clear();
        assertThrows(IOException.class, () -> DownloadSources.withSource(RELEASE, FASTLY_IPV4, null, source -> {
            calls.add(source); throw new IOException("offline");
        }));
        assertEquals(List.of(FASTLY_IPV4), calls);
    }
    @Test void cacheExpiresAndDoesNotCrossArtifactOrAccept() {
        String key = RELEASE + "\napplication/octet-stream";
        DownloadSources.cacheRanking(key, List.of(DIRECT), 100);
        assertEquals(List.of(DIRECT), DownloadSources.cachedRanking(key, 101));
        assertNull(DownloadSources.cachedRanking(RELEASE + ".sha256\napplication/octet-stream", 101));
        assertNull(DownloadSources.cachedRanking(RELEASE + "\napplication/json", 101));
        assertNull(DownloadSources.cachedRanking(key, 100 + Duration.ofSeconds(61).toNanos()));
    }
    @Test void cancelledBeforeOrDuringAttemptNeverFallsThrough() {
        AtomicBoolean cancelled = new AtomicBoolean(true); AtomicInteger calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> DownloadSources.withSource(RELEASE, DIRECT, cancelled, source -> calls.incrementAndGet()));
        cancelled.set(false);
        assertThrows(CancellationException.class, () -> DownloadSources.withSource(RELEASE, DIRECT, cancelled, source -> {
            calls.incrementAndGet(); cancelled.set(true); throw new IOException("body closed");
        }));
        assertEquals(1, calls.get());
    }
    @Test void actualRangeSamplingRanksFastBodyAndBoundsIgnoredRange() throws Exception {
        HttpServer server = server();
        server.createContext("/fast", exchange -> {
            assertEquals("bytes=0-65535", exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-65535/1048576");
            exchange.sendResponseHeaders(206, 65536); exchange.getResponseBody().write(new byte[65536]); exchange.close();
        });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 65536);
            try { for (int i = 0; i < 8; i++) { Thread.sleep(60); exchange.getResponseBody().write(new byte[8192]); exchange.getResponseBody().flush(); } }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/ignored", exchange -> {
            exchange.sendResponseHeaders(200, 4 * 1024 * 1024);
            try { for (int i = 0; i < 512; i++) exchange.getResponseBody().write(new byte[8192]); }
            catch (IOException clientClosedBoundedProbe) { }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var fast = DownloadSources.sample(DIRECT, uri(server, "/fast"), "application/octet-stream", null);
            var slow = DownloadSources.sample(DIRECT, uri(server, "/slow"), "application/octet-stream", null);
            var ignored = DownloadSources.sample(DIRECT, uri(server, "/ignored"), "application/octet-stream", null);
            assertNotNull(fast); assertNotNull(slow); assertNotNull(ignored);
            assertTrue(DownloadSources.score(fast) < DownloadSources.score(slow)); assertEquals(65536, ignored.bytes());
        } finally { server.stop(0); }
    }
    @Test void malformedRangeHtmlStatusRedirectAndStalledBodyAreRejected() throws Exception {
        HttpServer server = server();
        server.createContext("/bad-range", e -> { e.getResponseHeaders().set("Content-Range", "bytes 1-64/65"); e.sendResponseHeaders(206, 64); e.getResponseBody().write(new byte[64]); e.close(); });
        server.createContext("/html", e -> { e.getResponseHeaders().set("Content-Type", "text/html"); e.sendResponseHeaders(200, 64); e.getResponseBody().write(new byte[64]); e.close(); });
        server.createContext("/bare-html", e -> { byte[] html = "<!doctype html><html>blocked</html>".getBytes(); e.sendResponseHeaders(200, html.length); e.getResponseBody().write(html); e.close(); });
        server.createContext("/status", e -> { e.sendResponseHeaders(503, -1); e.close(); });
        server.createContext("/redirect", e -> { e.getResponseHeaders().set("Location", "http://127.0.0.1/private"); e.sendResponseHeaders(302, -1); e.close(); });
        CountDownLatch headers = new CountDownLatch(1);
        server.createContext("/stall", e -> { e.sendResponseHeaders(200, 65536); headers.countDown(); try { Thread.sleep(10000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } finally { e.close(); } });
        server.start();
        try {
            assertNull(DownloadSources.sample(DIRECT, uri(server, "/bad-range"), "application/octet-stream", null));
            assertNull(DownloadSources.sample(DIRECT, uri(server, "/html"), "application/octet-stream", null));
            assertNull(DownloadSources.sample(DIRECT, uri(server, "/bare-html"), "application/octet-stream", null));
            assertThrows(IOException.class, () -> DownloadSources.sample(DIRECT, uri(server, "/status"), "application/octet-stream", null));
            assertThrows(IOException.class, () -> DownloadSources.sample(DIRECT, uri(server, "/redirect"), "application/octet-stream", null));
            AtomicBoolean cancelled = new AtomicBoolean();
            var future = CompletableFuture.runAsync(() -> {
                assertThrows(Exception.class, () -> DownloadSources.sample(DIRECT, uri(server, "/stall"), "application/octet-stream", cancelled));
            });
            assertTrue(headers.await(2, TimeUnit.SECONDS)); cancelled.set(true); future.get(2, TimeUnit.SECONDS);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                    () -> DownloadSources.sample(DIRECT, uri(server, "/stall"), "application/octet-stream", null)));
        } finally { server.stop(0); }
    }
    @Test void manifestIdentityIsNotAnUnrelatedOrUnknownSchemaDocument() throws Exception {
        String valid = "{\"schema_version\":1,\"project\":\"TuneWeave\",\"api_version\":\"v1\",\"artifacts\":[{}]}";
        assertEquals("v1", ApiServerFetcher.parseManifest(valid).getApiVersion());
        for (String bad : List.of("null", "{}", "<html>", valid.replace("TuneWeave", "Other"), valid.replace(":1,", ":2,"), valid.replace("v1", "oops")))
            assertThrows(IOException.class, () -> ApiServerFetcher.parseManifest(bad));
    }
    @Test void compatibilityProfilesPreserveMissingAndRejectCoercionOrMalformedDeclarations() throws Exception {
        String base = "{\"schema_version\":1,\"project\":\"TuneWeave\",\"api_version\":\"v1\",\"artifacts\":[{}]";
        assertNull(ApiServerFetcher.parseManifest(base + "}").getCompatibilityProfiles());
        assertEquals(List.of("future-profile"), ApiServerFetcher.parseManifest(base
                + ",\"compatibility_profiles\":[\"future-profile\"]}").getCompatibilityProfiles());
        for (String bad : List.of("null", "[]", "[1]", "[\"UPPER\"]", "[\"a\",\"a\"]", "[true]", "{}"))
            assertThrows(IOException.class, () -> ApiServerFetcher.parseManifest(base + ",\"compatibility_profiles\":" + bad + "}"));
    }
    @Test void checksumMustBeASha256ValueRatherThanAnHtmlOrMalformedResponse() throws Exception {
        String digest = "ab".repeat(32);
        assertEquals(digest, ApiServerFetcher.parseChecksum(digest.toUpperCase(Locale.ROOT) + "  executable"));
        for (String invalid : List.of("", "<html>failed</html>", "ab".repeat(31), "gz".repeat(32)))
            assertThrows(IOException.class, () -> ApiServerFetcher.parseChecksum(invalid));
    }
    private static HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); return server;
    }
    private static URI uri(HttpServer server, String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
}
