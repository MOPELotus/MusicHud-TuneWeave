package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.EnumMap;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Owns TuneWeave client-mode transport and credential persistence. */
final class TuneWeaveGateway {
    private static final String CREDENTIAL_FORMAT = "tuneweave_credential_v1";
    private static final String CREDENTIAL_PREFIX = "twc1_";

    private final ClientConfig config;
    @FunctionalInterface
    interface Transport {
        TuneWeaveApiClient.TuneWeaveResponse request(String baseUrl, String method, String path,
                Map<String, String> query, JsonElement body, List<String> credentials);
    }
    private final Transport transport;
    private final ThreadLocal<RequestContext> boundContext = new ThreadLocal<>();

    TuneWeaveGateway() { this(ClientConfig.getInstance()); }
    TuneWeaveGateway(ClientConfig config) { this(config, TuneWeaveApiClient::requestAt); }
    TuneWeaveGateway(ClientConfig config, Transport transport) {
        this.config = Objects.requireNonNull(config);
        this.transport = Objects.requireNonNull(transport);
    }

    /** Client-local only; deliberately not a record so secrets never appear in generated toString. */
    private static final class RequestContext {
        final String baseUrl;
        final TuneWeavePlatform platform;
        final Map<TuneWeavePlatform, String> credentials;
        RequestContext(String baseUrl, TuneWeavePlatform platform, Map<TuneWeavePlatform, String> credentials) {
            this.baseUrl = baseUrl;
            this.platform = platform;
            this.credentials = Map.copyOf(credentials);
        }
    }

    private RequestContext snapshot() {
        RequestContext active = boundContext.get();
        if (active == null) {
            Map<TuneWeavePlatform, String> credentials = new EnumMap<>(TuneWeavePlatform.class);
            for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
                credentials.put(platform, config.getTuneWeaveCredential(platform.apiName()));
            }
            active = new RequestContext(config.getTuneWeaveBaseUrl(), defaultPlatform(), credentials);
        }
        return active;
    }

    <T> Supplier<T> capture(Supplier<T> operation) {
        RequestContext context = snapshot();
        return () -> within(context, operation);
    }

    <T, R> java.util.function.Function<T, R> captureFunction(java.util.function.Function<T, R> operation) {
        RequestContext context = snapshot();
        return value -> within(context, () -> operation.apply(value));
    }

    private <T> T within(RequestContext context, Supplier<T> operation) {
            RequestContext previous = boundContext.get();
            boundContext.set(context);
            try {
                requireCurrentContext();
                T result = operation.get();
                requireCurrentContext();
                return result;
            } finally {
                if (previous == null) boundContext.remove(); else boundContext.set(previous);
            }
    }

    private void requireCurrentContext() {
        RequestContext context = boundContext.get();
        if (context == null) return;
        if (!context.baseUrl.equals(config.getTuneWeaveBaseUrl())) {
            throw new CancellationException("TuneWeave endpoint changed during request");
        }
        for (var credential : context.credentials.entrySet()) {
            if (!credential.getValue().equals(config.getTuneWeaveCredential(credential.getKey().apiName()))) {
                throw new CancellationException("TuneWeave account changed during request");
            }
        }
    }

    TuneWeavePlatform defaultPlatform() {
        if (boundContext.get() != null) return boundContext.get().platform;
        return TuneWeavePlatform.fromApiName(config.getDefaultMusicPlatform());
    }

    void setDefaultPlatform(TuneWeavePlatform platform) {
        config.setDefaultMusicPlatform(Objects.requireNonNull(platform).apiName());
        config.save();
    }

    boolean isAvailable() {
        return TuneWeaveApiClient.isAvailableAt(config.getTuneWeaveBaseUrl());
    }

    boolean hasCredential(TuneWeavePlatform platform) {
        return !credential(platform).isBlank();
    }

    String credential(TuneWeavePlatform platform) {
        requireCurrentContext();
        if (boundContext.get() != null) return boundContext.get().credentials.get(platform);
        return config.getTuneWeaveCredential(Objects.requireNonNull(platform).apiName());
    }

    TuneWeaveApiClient.TuneWeaveResponse requestForPlatform(
            TuneWeavePlatform platform, String method, String path,
            Map<String, String> query, JsonElement body) {
        String value = credential(platform);
        List<String> credentials = value.isBlank() ? List.of() : List.of(value);
        return request(method, path, query, body, credentials);
    }

    TuneWeaveApiClient.TuneWeaveResponse requestWithAllCredentials(
            String method, String path, Map<String, String> query, JsonElement body) {
        List<String> credentials = new ArrayList<>(TuneWeavePlatform.values().length);
        for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
            String value = credential(platform);
            if (!value.isBlank()) credentials.add(value);
        }
        return request(method, path, query, body, credentials);
    }

    TuneWeaveApiClient.TuneWeaveResponse requestWithoutCredential(
            String method, String path, Map<String, String> query, JsonElement body) {
        return request(method, path, query, body, List.of());
    }

    synchronized void saveCredential(TuneWeavePlatform expectedPlatform, JsonElement element) {
        JsonObject credential = TuneWeaveJson.object(element);
        String format = TuneWeaveJson.requiredString(credential, "format");
        String platform = TuneWeaveJson.requiredString(credential, "platform");
        String value = TuneWeaveJson.requiredString(credential, "value");
        if (!CREDENTIAL_FORMAT.equals(format)
                || TuneWeaveReference.requirePlatform(platform) != expectedPlatform
                || !value.startsWith(CREDENTIAL_PREFIX)) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "TuneWeave returned an invalid caller credential", false);
        }
        config.setTuneWeaveCredential(expectedPlatform.apiName(), value);
        config.setDefaultMusicPlatform(expectedPlatform.apiName());
        config.save();
    }

    synchronized void clearCredential(TuneWeavePlatform platform) {
        config.clearTuneWeaveCredential(Objects.requireNonNull(platform).apiName());
        config.save();
    }

    synchronized boolean clearCredentialIfUnchanged(TuneWeavePlatform platform, String expected, Runnable clearSession) {
        if (!Objects.equals(config.getTuneWeaveCredential(platform.apiName()), expected)) return false;
        clearSession.run();
        clearCredential(platform);
        return true;
    }

    synchronized void publishIfCredentialUnchanged(TuneWeavePlatform platform, String expected, Runnable publish) {
        if (!Objects.equals(config.getTuneWeaveCredential(platform.apiName()), expected)) {
            throw new CancellationException("TuneWeave account changed before profile publication");
        }
        publish.run();
    }

    private TuneWeaveApiClient.TuneWeaveResponse request(
            String method, String path, Map<String, String> query,
            JsonElement body, List<String> credentials) {
        requireCurrentContext();
        RequestContext context = boundContext.get();
        var response = transport.request(
                context == null ? config.getTuneWeaveBaseUrl() : context.baseUrl,
                method, path, query, body, credentials);
        requireCurrentContext();
        return response;
    }
}
