package indi.mopelotus.musichud.server.api;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publisher evidence for an explicitly configured older service, including external instances. */
public final class ServiceReleaseProfiles {
    private record Cached(long expires, Set<String> profiles) {}
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    private ServiceReleaseProfiles() {}
    public static Set<String> forVersion(String version) {
        if (version == null || version.length() > 128 || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) return Set.of();
        synchronized (CACHE) {
            var cached = CACHE.get(version);
            if (cached != null && System.nanoTime() < cached.expires()) return cached.profiles();
        }
        Set<String> profiles = Set.of();
        var cancelled = new AtomicBoolean();
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> cancelled.set(true));
        String url = "https://github.com/MOPELotus/TuneWeave/releases/download/v" + version + "/release-manifest.json";
        try {
            var response = DownloadSources.open(url, ApiServerFetcher.DownloadProxy.DIRECT, "application/json", cancelled);
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length <= 1_048_576) profiles = parsePublished(version, new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) { /* Missing or unverified metadata remains unknown. */ }
        synchronized (CACHE) {
            while (CACHE.size() >= 64) CACHE.remove(CACHE.keySet().iterator().next());
            CACHE.put(version, new Cached(System.nanoTime() + TimeUnit.SECONDS.toNanos(profiles.isEmpty() ? 60 : 21_600), profiles));
        }
        return profiles;
    }
    static Set<String> parsePublished(String version, String json) throws java.io.IOException {
        var manifest = ApiServerFetcher.parseManifest(json);
        if (!version.equals(manifest.getVersion()) || !("v" + version).equals(manifest.getTag()))
            throw new java.io.IOException("Service release identity mismatch");
        if (manifest.getCompatibilityProfiles() != null) return Set.copyOf(manifest.getCompatibilityProfiles());
        return TuneWeaveCompatibility.LEGACY_VERSION.equals(version) && "v1".equals(manifest.getApiVersion())
                ? Set.of(TuneWeaveCompatibility.LEGACY_PROFILE) : Set.of();
    }
}
