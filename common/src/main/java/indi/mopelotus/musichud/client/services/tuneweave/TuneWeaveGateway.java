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
    private final Map<TuneWeavePlatform, CredentialState> states = new EnumMap<>(TuneWeavePlatform.class);
    private static final class CredentialState {
        volatile String value;
        CredentialState(String value) { this.value = value; }
    }
    private synchronized CredentialState state(TuneWeavePlatform platform) {
        String value = config.getTuneWeaveCredential(platform.apiName());
        CredentialState state = states.get(platform);
        if (state == null || !state.value.equals(value)) {
            state = new CredentialState(value);
            states.put(platform, state);
        }
        return state;
    }
    synchronized Object accountIdentity(TuneWeavePlatform platform) { return state(platform); }
    synchronized void publishIfAccountCurrent(TuneWeavePlatform platform, Object identity, Runnable publish) {
        if (state(platform) != identity) throw new CancellationException("TuneWeave account changed");
        publish.run();
    }
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
        final Map<TuneWeavePlatform, CredentialState> credentials;
        RequestContext(String baseUrl, TuneWeavePlatform platform, Map<TuneWeavePlatform, CredentialState> credentials) {
            this.baseUrl = baseUrl;
            this.platform = platform;
            this.credentials = Map.copyOf(credentials);
        }
    }

    private synchronized RequestContext snapshot() {
        RequestContext active = boundContext.get();
        if (active == null) {
            Map<TuneWeavePlatform, CredentialState> credentials = new EnumMap<>(TuneWeavePlatform.class);
            for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
                credentials.put(platform, state(platform));
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
        requireCurrentContext(context);
    }

    private void requireCurrentContext(RequestContext context) {
        if (!context.baseUrl.equals(config.getTuneWeaveBaseUrl())) {
            throw new CancellationException("TuneWeave endpoint changed during request");
        }
        for (var credential : context.credentials.entrySet()) {
            if (credential.getValue() != state(credential.getKey())) {
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
        if (boundContext.get() != null) return boundContext.get().credentials.get(platform).value;
        return config.getTuneWeaveCredential(Objects.requireNonNull(platform).apiName());
    }

    TuneWeaveApiClient.TuneWeaveResponse requestForPlatform(
            TuneWeavePlatform platform, String method, String path,
            Map<String, String> query, JsonElement body) {
        // beta.1 exposes these catalog reads only in public scope. Never apply this to
        // account libraries, playlists, track authorization, or mutations.
        if ("GET".equals(method) && (platform == TuneWeavePlatform.KUGOU || platform == TuneWeavePlatform.KUWO || platform == TuneWeavePlatform.MIGU)
                && (path.startsWith("/v1/albums/") || path.startsWith("/v1/artists/")))
            return requestWithoutCredential(method, path, query, body);
        return capture(() -> {
            String value = credential(platform);
            List<String> credentials = value.isBlank() ? List.of() : List.of(value);
            return request(method, path, query, body, credentials);
        }).get();
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
        JsonObject meta = new JsonObject(); meta.add("caller_credential", element);
        var values = indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveCredentialUpdates.parse(meta, List.of());
        String value = values.get(expectedPlatform.apiName());
        if (value == null || values.size() != 1) throw new TuneWeaveApiClient.TuneWeaveException("Unexpected credential platform", false);
        config.setTuneWeaveCredential(expectedPlatform.apiName(), value);
        states.put(expectedPlatform, new CredentialState(value));
        config.setDefaultMusicPlatform(expectedPlatform.apiName());
        config.save();
    }

    synchronized void clearCredential(TuneWeavePlatform platform) {
        config.clearTuneWeaveCredential(Objects.requireNonNull(platform).apiName());
        states.put(platform, new CredentialState(""));
        config.save();
    }

    synchronized boolean clearCredentialIfCurrent(TuneWeavePlatform platform, Object expected, Runnable clearSession) {
        if (state(platform) != expected) return false;
        clearSession.run();
        clearCredential(platform);
        return true;
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
        RequestContext context = snapshot();
        Map<TuneWeavePlatform, String> sent = new EnumMap<>(TuneWeavePlatform.class);
        synchronized (this) {
            requireCurrentContext(context);
            context.credentials.forEach((platform, state) -> {
                if (!state.value.isBlank() && credentials.contains(state.value)) sent.put(platform, state.value);
            });
            if (!sent.values().containsAll(credentials))
                throw new CancellationException("TuneWeave credential changed before request");
        }
        try {
            var response = transport.request(context.baseUrl, method, path, query, body, credentials);
            applyUpdates(context, sent, response.credentialUpdates(), false, null);
            requireCurrentContext(context);
            return response;
        } catch (TuneWeaveApiClient.TuneWeaveException error) {
            boolean invalid = "authentication_required".equals(error.getCode()) || "conflict".equals(error.getCode());
            applyUpdates(context, sent, error.getCredentialUpdates(), invalid, error.getPlatform());
            throw error;
        }
    }

    private synchronized void applyUpdates(RequestContext context, Map<TuneWeavePlatform, String> sent,
                                           Map<String, String> updates, boolean invalid, TuneWeavePlatform affected) {
        requireCurrentContext(context);
        // Even a late failed request must never delete or overwrite a newer login/rotation.
        for (var entry : sent.entrySet()) {
            if (!state(entry.getKey()).value.equals(entry.getValue()))
                throw new CancellationException("TuneWeave credential changed during request");
        }
        if (invalid) {
            if (affected != null) {
                if (sent.containsKey(affected)) clearCredential(affected);
            } else if (sent.size() == 1) clearCredential(sent.keySet().iterator().next());
            return;
        }
        for (var entry : updates.entrySet()) {
            TuneWeavePlatform platform = TuneWeavePlatform.requireApiName(entry.getKey());
            if (!sent.containsKey(platform))
                throw new TuneWeaveApiClient.TuneWeaveException("Unexpected TuneWeave credential update platform", false);
        }
        for (var entry : updates.entrySet()) {
            TuneWeavePlatform platform = TuneWeavePlatform.requireApiName(entry.getKey());
            config.setTuneWeaveCredential(platform.apiName(), entry.getValue());
            context.credentials.get(platform).value = entry.getValue();
        }
        if (!updates.isEmpty()) config.save();
    }
}
