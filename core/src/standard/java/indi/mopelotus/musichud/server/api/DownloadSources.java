package indi.mopelotus.musichud.server.api;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded public-release transport shared by the standard edition's downloaders. */
public final class DownloadSources {
    private DownloadSources() {}
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ExecutorService PROBES = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore PROBE_SLOTS = new Semaphore(3);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "musichud-download-deadlines"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    private static final int SAMPLE_BYTES = 64 * 1024;
    private static final long CACHE_NANOS = Duration.ofSeconds(60).toNanos();
    private record Cached(long expires, List<ApiServerFetcher.DownloadProxy> sources) {}
    record Sample(ApiServerFetcher.DownloadProxy source, long bytes, long nanos, long firstByteNanos) {}
    @FunctionalInterface public interface SourceOperation<T> {
        T run(ApiServerFetcher.DownloadProxy source) throws Exception;
    }

    public static <T> T withSource(String original, ApiServerFetcher.DownloadProxy choice,
                                  AtomicBoolean cancelled, SourceOperation<T> operation) throws Exception {
        return withSource(original, choice, "application/octet-stream", cancelled, operation);
    }
    public static <T> T withSource(String original, ApiServerFetcher.DownloadProxy choice, String accept,
                                  AtomicBoolean cancelled, SourceOperation<T> operation) throws Exception {
        validateOriginal(original);
        Objects.requireNonNull(choice); Objects.requireNonNull(operation);
        List<ApiServerFetcher.DownloadProxy> sources = choice == ApiServerFetcher.DownloadProxy.AUTO
                ? rank(original, accept, cancelled) : List.of(choice);
        Exception failure = null;
        for (var source : sources) {
            checkCancelled(cancelled);
            try { T result = operation.run(source); checkCancelled(cancelled); return result; }
            catch (CancellationException cancelledError) { throw cancelledError; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            catch (Exception error) {
                failure = error;
                synchronized (CACHE) { CACHE.remove(original + "\n" + accept); }
            }
        }
        // Do not keep a failed ranking: the next user retry measures current connectivity again.
        synchronized (CACHE) { CACHE.remove(original + "\n" + accept); }
        checkCancelled(cancelled);
        throw new IOException("All selected download sources failed", failure);
    }

    /** The returned body must be closed. Cancellation closes even a stalled body read. */
    public static HttpResponse<InputStream> open(String original, ApiServerFetcher.DownloadProxy source,
                                               String accept, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        validateOriginal(original);
        if (source == ApiServerFetcher.DownloadProxy.AUTO) throw new IllegalArgumentException("Select a concrete download source first");
        return request(URI.create(source.resolveUrl(original)), accept, cancelled, null, Duration.ofMinutes(15));
    }

    /** Only project-owned public release locations may be transformed into mirror requests. */
    public static void validateOriginal(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String rawPath = uri.getRawPath();
            if (rawPath == null) throw new IllegalArgumentException();
            // Existing mod asset URLs encode the MC version separator as %2B. No encoded path separators or dot segments.
            boolean encodedPath = rawPath.substring(0, rawPath.lastIndexOf('/') + 1).contains("%")
                    || rawPath.replaceAll("(?i)%2b", "+").contains("%");
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getFragment() != null || uri.getHost() == null || !uri.normalize().equals(uri)
                    || encodedPath || rawPath.contains("\\")) throw new IllegalArgumentException();
            String path = uri.getPath();
            boolean release = "github.com".equals(uri.getHost()) && uri.getRawQuery() == null
                    && path.matches("/MOPELotus/(TuneWeave|MusicHud-TuneWeave)/releases/download/[^/]+/[^/]+");
            boolean manifest = "raw.githubusercontent.com".equals(uri.getHost()) && uri.getRawQuery() == null
                    && path.equals("/MOPELotus/TuneWeave/main/release-manifest.json");
            boolean signer = "api.github.com".equals(uri.getHost()) && (
                    (path.equals("/repos/MOPELotus/TuneWeave/contents/tools/auxiliary/TuneWeave-SodaSigner.exe")
                            && "ref=dev".equals(uri.getRawQuery()))
                    || (path.matches("/repos/MOPELotus/TuneWeave/git/blobs/[0-9a-f]{40}") && uri.getRawQuery() == null));
            if (!release && !manifest && !signer) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IOException("Untrusted public download location"); }
    }

    private static boolean allowedRedirect(URI target, String initialHost) {
        if (!"https".equals(target.getScheme()) || target.getRawUserInfo() != null || target.getPort() != -1
                || target.getFragment() != null || target.getHost() == null) return false;
        String host = target.getHost();
        return host.equals(initialHost) || Set.of("github.com", "api.github.com", "raw.githubusercontent.com",
                "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(host);
    }
    static HttpResponse<InputStream> request(URI uri, String accept, AtomicBoolean cancelled,
                                                      String range, Duration lifetime)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + lifetime.toNanos();
        String initialHost = uri.getHost();
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCancelled(cancelled);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(range == null ? 20 : 3))
                    .header("User-Agent", "MusicHud-TuneWeave").header("Accept", accept)
                    .header("Accept-Encoding", "identity");
            if (range != null) builder.header("Range", range);
            var future = HTTP.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            boolean received = false;
            try {
                while (true) {
                    checkCancelled(cancelled);
                    if (System.nanoTime() >= deadline) throw new IOException("Download request timed out");
                    try { response = future.get(100, TimeUnit.MILLISECONDS); received = true; break; }
                    catch (TimeoutException waiting) { /* cancellation and deadline checked above */ }
                }
            } catch (ExecutionException error) {
                throw new IOException("Public download request failed", error.getCause());
            } finally {
                if (!received) {
                    future.thenAccept(abandoned -> { try { abandoned.body().close(); } catch (IOException ignored) { } });
                    future.cancel(true);
                }
            }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                response.body().close();
                URI target;
                try { target = uri.resolve(response.headers().firstValue("Location").orElseThrow()); }
                catch (RuntimeException invalid) { throw new IOException("Invalid download redirect"); }
                if (!allowedRedirect(target, initialHost)) throw new IOException("Untrusted download redirect");
                uri = target; continue;
            }
            if (status != 200 && !(range != null && status == 206)) {
                response.body().close(); throw new IOException("Public download returned HTTP " + status);
            }
            InputStream input = new GuardedBody(response.body(), cancelled, deadline);
            return new BodyResponse(response, input);
        }
        throw new IOException("Too many public download redirects");
    }

    private static List<ApiServerFetcher.DownloadProxy> rank(String original, String accept, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        String key = original + "\n" + accept;
        List<ApiServerFetcher.DownloadProxy> cached = cachedRanking(key, System.nanoTime());
        if (cached != null) return cached;
        List<CompletableFuture<Sample>> pending = new ArrayList<>();
        for (var source : ApiServerFetcher.DownloadProxy.values()) {
            if (source == ApiServerFetcher.DownloadProxy.AUTO) continue;
            pending.add(CompletableFuture.supplyAsync(() -> {
                boolean acquired = false;
                try {
                    while (!(acquired = PROBE_SLOTS.tryAcquire(100, TimeUnit.MILLISECONDS))) checkCancelled(cancelled);
                    checkCancelled(cancelled);
                    return sample(source, URI.create(source.resolveUrl(original)), accept, cancelled);
                } catch (Exception unavailable) { return null; }
                finally { if (acquired) PROBE_SLOTS.release(); }
            }, PROBES));
        }
        List<Sample> samples = new ArrayList<>();
        try {
            for (var future : pending) {
                while (true) {
                    checkCancelled(cancelled);
                    try { Sample sample = future.get(100, TimeUnit.MILLISECONDS); if (sample != null) samples.add(sample); break; }
                    catch (TimeoutException waiting) { /* bounded probes, cancellable wait */ }
                    catch (ExecutionException unavailable) { break; }
                }
            }
        } finally { pending.forEach(future -> future.cancel(true)); }
        checkCancelled(cancelled);
        // Equal 64 KiB estimates combine first-byte delay and measured throughput; tiny files rank by completion time.
        samples.sort(Comparator.comparingDouble(DownloadSources::score));
        List<ApiServerFetcher.DownloadProxy> ordered = new ArrayList<>();
        for (Sample sample : samples) ordered.add(sample.source);
        // A failed short probe does not forbid a subsequent real request (some endpoints reject Range).
        for (var source : ApiServerFetcher.DownloadProxy.values())
            if (source != ApiServerFetcher.DownloadProxy.AUTO && !ordered.contains(source)) ordered.add(source);
        List<ApiServerFetcher.DownloadProxy> result = List.copyOf(ordered);
        if (!samples.isEmpty()) cacheRanking(key, result, System.nanoTime());
        return result;
    }
    static List<ApiServerFetcher.DownloadProxy> cachedRanking(String key, long now) {
        synchronized (CACHE) {
            Cached value = CACHE.get(key);
            if (value != null && now < value.expires) return value.sources;
            CACHE.remove(key); return null;
        }
    }
    static void cacheRanking(String key, List<ApiServerFetcher.DownloadProxy> sources, long now) {
        synchronized (CACHE) {
            while (CACHE.size() >= 64) CACHE.remove(CACHE.keySet().iterator().next());
            CACHE.put(key, new Cached(now + CACHE_NANOS, List.copyOf(sources)));
        }
    }
    static Sample sample(ApiServerFetcher.DownloadProxy source, URI uri, String accept, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        var response = request(uri, accept, cancelled, "bytes=0-" + (SAMPLE_BYTES - 1), Duration.ofSeconds(3));
        try (var input = response.body()) {
            long expected = -1;
            if (response.statusCode() == 206) {
                String range = response.headers().firstValue("Content-Range").orElse("");
                var match = java.util.regex.Pattern.compile("bytes 0-([0-9]+)/([0-9]+|\\*)").matcher(range);
                if (!match.matches()) return null;
                try { expected = Long.parseLong(match.group(1)) + 1; }
                catch (NumberFormatException invalid) { return null; }
                if (expected < 1 || expected > SAMPLE_BYTES) return null;
                if (!"*".equals(match.group(2))) {
                    try { if (Long.parseLong(match.group(2)) < expected) return null; }
                    catch (NumberFormatException invalid) { return null; }
                }
            }
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if ((type.contains("text/html") || type.contains("application/xhtml"))) return null;
            byte[] buffer = new byte[8192]; long bytes = 0, first = 0;
            while (bytes < SAMPLE_BYTES) {
                checkCancelled(cancelled);
                int n = input.read(buffer, 0, (int) Math.min(buffer.length, SAMPLE_BYTES - bytes));
                if (n < 0) break;
                if (n > 0 && first == 0) {
                    first = System.nanoTime() - start;
                    String prefix = new String(buffer, 0, Math.min(n, 128), java.nio.charset.StandardCharsets.US_ASCII)
                            .stripLeading().toLowerCase(Locale.ROOT);
                    if (prefix.startsWith("<html") || prefix.startsWith("<!doctype html")) return null;
                }
                bytes += n;
            }
            if (expected >= 0 && bytes != expected) return null;
            return bytes == 0 ? null : new Sample(source, bytes, System.nanoTime() - start, first);
        }
    }
    static double score(Sample sample) {
        return sample.firstByteNanos + (double) SAMPLE_BYTES * Math.max(1, sample.nanos - sample.firstByteNanos) / sample.bytes;
    }
    static void checkCancelled(AtomicBoolean cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.get())
            throw new CancellationException("Download cancelled");
    }
    private static final class GuardedBody extends FilterInputStream {
        private final AtomicBoolean cancelled; private final long deadline;
        private final ScheduledFuture<?> watcher; private volatile boolean closed;
        private volatile long lastProgress = System.nanoTime();
        GuardedBody(InputStream input, AtomicBoolean cancelled, long deadline) {
            super(input); this.cancelled = cancelled; this.deadline = deadline;
            watcher = WATCHDOG.scheduleAtFixedRate(() -> {
                if (cancelled != null && cancelled.get() || System.nanoTime() >= deadline
                        || System.nanoTime() - lastProgress >= Duration.ofSeconds(20).toNanos()) {
                    try { close(); } catch (IOException ignored) { }
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
        }
        private void check() throws IOException {
            checkCancelled(cancelled);
            if (System.nanoTime() >= deadline) throw new IOException("Download body timed out");
            if (closed) throw new IOException("Download body closed");
        }
        @Override public int read() throws IOException { check(); int value = in.read(); if (value >= 0) lastProgress = System.nanoTime(); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            check(); int count = in.read(bytes, offset, length); if (count > 0) lastProgress = System.nanoTime(); return count;
        }
        @Override public void close() throws IOException { closed = true; watcher.cancel(false); super.close(); }
    }
    private record BodyResponse(HttpResponse<InputStream> response, InputStream body) implements HttpResponse<InputStream> {
        public int statusCode() { return response.statusCode(); }
        public HttpRequest request() { return response.request(); }
        public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return response.headers(); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return response.sslSession(); }
        public URI uri() { return response.uri(); }
        public HttpClient.Version version() { return response.version(); }
    }
}
