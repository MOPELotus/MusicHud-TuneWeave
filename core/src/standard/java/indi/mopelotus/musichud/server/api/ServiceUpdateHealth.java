package indi.mopelotus.musichud.server.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded, credential-free local update verification; deliberately has no API retry policy. */
public final class ServiceUpdateHealth {
    private static final Duration MAX_BUDGET = Duration.ofSeconds(2);
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();

    private ServiceUpdateHealth() {}

    public static Optional<String> versionAt(String localRootUrl) {
        return serverVersionAt(localRootUrl, MAX_BUDGET);
    }

    /** Read-only TCP handshake probe; TIME_WAIT is not mistaken for a live external listener. */
    public static boolean localPortAvailableForStart(String localRootUrl) {
        if (!ApiBinaryUpdateService.isLocalServiceUrl(localRootUrl)) return false;
        CompletableFuture<java.net.InetAddress[]> resolution = null;
        Thread resolver = null;
        try {
            URI uri = URI.create(localRootUrl);
            if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) return false;
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            if (port <= 0 || port > 65535) return false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            resolution = new CompletableFuture<>();
            var addresses = resolution;
            resolver = Thread.ofVirtual().start(() -> {
                try { addresses.complete(java.net.InetAddress.getAllByName(uri.getHost())); }
                catch (Exception unknown) { addresses.completeExceptionally(unknown); }
            });
            var resolved = resolution.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (resolved.length == 0) return false;
            for (var address : new java.util.LinkedHashSet<>(java.util.List.of(resolved))) {
                if (!address.isLoopbackAddress() || deadline - System.nanoTime() <= 0) return false;
                try (var socket = new java.net.Socket()) {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMillis <= 0) return false;
                    socket.connect(new java.net.InetSocketAddress(address, port), (int) Math.min(500, remainingMillis));
                    return false; // An external listener answered. Never manage or stop it.
                } catch (java.net.ConnectException refused) {
                    // Timeout, routing failures and other ambiguous socket errors are not evidence of a free port.
                    String message = refused.getMessage();
                    if (message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("refused")) return false;
                }
            }
            return deadline - System.nanoTime() > 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception occupiedOrUnknown) {
            return false;
        } finally {
            if (resolution != null && !resolution.isDone()) resolution.cancel(true);
            if (resolver != null && resolver.isAlive()) resolver.interrupt();
        }
    }

    /** Read-only check of the service explicitly configured by the user; does not manage external processes. */
    public static Optional<String> configuredServerVersionAt(String configuredBaseUrl) {
        return probe(configuredBaseUrl, MAX_BUDGET, false);
    }

    static Optional<String> serverVersionAt(String baseUrl, Duration remainingBudget) {
        return probe(baseUrl, remainingBudget, true);
    }

    private static Optional<String> probe(String baseUrl, Duration remainingBudget, boolean localRootOnly) {
        if ((localRootOnly && !ApiBinaryUpdateService.isLocalServiceUrl(baseUrl)) || remainingBudget == null
                || remainingBudget.isNegative() || remainingBudget.isZero()) return Optional.empty();
        Duration budget = remainingBudget.compareTo(MAX_BUDGET) < 0 ? remainingBudget : MAX_BUDGET;
        CompletableFuture<HttpResponse<byte[]>> response = null;
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            String normalized = indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.normalizeBaseUrl(baseUrl);
            URI base = URI.create(normalized);
            if (localRootOnly && base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath()))
                return Optional.empty();
            URI health = URI.create(normalized + "/healthz");
            var request = HttpRequest.newBuilder(health).timeout(budget).header("Accept", "application/json").GET().build();
            response = HTTP.sendAsync(request, info -> new LimitedBody());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return Optional.empty();
            var result = response.get(remaining, TimeUnit.NANOSECONDS);
            if (result.statusCode() != 200 || System.nanoTime() - deadline >= 0) return Optional.empty();
            JsonElement parsed = JsonParser.parseString(new String(result.body(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return Optional.empty();
            var envelope = parsed.getAsJsonObject();
            if (envelope.has("error") && !envelope.get("error").isJsonNull()) return Optional.empty();
            JsonElement data = envelope.get("data");
            if (data == null || !data.isJsonObject()) return Optional.empty();
            JsonElement version = data.getAsJsonObject().get("version");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString())
                return Optional.empty();
            String value = version.getAsString();
            return value.isBlank() || value.length() > 128 ? Optional.empty() : Optional.of(value);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        } finally {
            // Cancels the actual exchange, including a server that sent headers but never finishes its body.
            if (response != null && !response.isDone()) response.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new java.io.IOException("Service health response is too large"));
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
