package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveCredentialRotationTest {
    static final TuneWeavePlatform PLATFORM = TuneWeavePlatform.SODA;
    final Map<String, String> credentials = new HashMap<>(Map.of("soda", "twc1_original"));
    final AtomicInteger saves = new AtomicInteger();
    final ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
            new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                case "getDefaultMusicPlatform" -> "soda";
                case "getTuneWeaveCredential" -> credentials.getOrDefault(args[0], "");
                case "setTuneWeaveCredential" -> { credentials.put((String) args[0], (String) args[1]); yield null; }
                case "clearTuneWeaveCredential" -> { credentials.remove(args[0]); yield null; }
                case "save" -> { saves.incrementAndGet(); yield null; }
                default -> null;
            });
    static TuneWeaveApiClient.TuneWeaveResponse response(String credential) {
        return new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonArray(), new JsonObject(),
                credential == null ? Map.of() : Map.of("soda", credential));
    }
    static void request(TuneWeaveGateway gateway) {
        gateway.requestForPlatform(PLATFORM, "GET", "/v1/account/playlists", Map.of(), null);
    }

    @Test void rotationPreservesAccountAndCapturedJobsUseNewestCredential() {
        var count = new AtomicInteger();
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
            assertEquals(List.of(count.getAndIncrement() == 0 ? "twc1_original" : "twc1_rotated"), sent);
            return response("twc1_rotated");
        });
        Object identity = gateway.accountIdentity(PLATFORM);
        var queued = gateway.capture(() -> { request(gateway); return gateway.credential(PLATFORM); });
        assertEquals("twc1_rotated", queued.get());
        assertEquals("twc1_rotated", queued.get());
        assertSame(identity, gateway.accountIdentity(PLATFORM));
        gateway.publishIfAccountCurrent(PLATFORM, identity, () -> {});
    }

    @Test void businessFailureStillPersistsVerifiedRotation() {
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
            throw new TuneWeaveApiClient.TuneWeaveException("unavailable", 502, "upstream_error", false,
                    JsonNull.INSTANCE, Map.of("soda", "twc1_rotated"));
        });
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> request(gateway));
        assertEquals("twc1_rotated", credentials.get("soda"));
    }

    @Test void lateSuccessAndLateFailureCannotOverwriteNewLogin() {
        for (boolean failure : List.of(false, true)) {
            credentials.put("soda", "twc1_original");
            var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
                credentials.put("soda", "twc1_new_login");
                if (failure) throw new TuneWeaveApiClient.TuneWeaveException("expired", 401,
                        "authentication_required", false, JsonNull.INSTANCE, Map.of("soda", "twc1_late"));
                return response("twc1_late");
            });
            assertThrows(CancellationException.class, () -> request(gateway));
            assertEquals("twc1_new_login", credentials.get("soda"));
        }
    }

    @Test void explicitInvalidSessionDiscardsOnlyAffectedCredentialAndPendingWork() {
        credentials.put("migu", "twc1_other");
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
            throw new TuneWeaveApiClient.TuneWeaveException("expired", 401,
                    "authentication_required", false, JsonNull.INSTANCE, Map.of("soda", "twc1_bad"));
        });
        var old = gateway.capture(() -> gateway.credential(PLATFORM));
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> request(gateway));
        assertFalse(credentials.containsKey("soda"));
        assertEquals("twc1_other", credentials.get("migu"));
        assertThrows(CancellationException.class, old::get);
    }

    @Test void multiPlatformFailureUsesNormalizedAffectedPlatformOnly() {
        credentials.put("migu", "twc1_migu");
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
            throw new TuneWeaveApiClient.TuneWeaveException("expired", 401, "authentication_required", false,
                    JsonNull.INSTANCE, Map.of("soda", "twc1_ignored"), TuneWeavePlatform.MIGU);
        });
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> gateway.requestWithAllCredentials("GET", "/v1/resolve", Map.of(), null));
        assertFalse(credentials.containsKey("migu")); assertEquals("twc1_original", credentials.get("soda"));
    }

    @Test void publicCatalogReadsOmitCredentialsWhilePersonalReadsAndMutationsKeepThem() {
        for (var platform : List.of(TuneWeavePlatform.KUGOU, TuneWeavePlatform.KUWO, TuneWeavePlatform.MIGU)) {
            credentials.put(platform.apiName(), "twc1_catalog_" + platform.apiName());
            var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
                if ("GET".equals(method) && (path.startsWith("/v1/albums/") || path.startsWith("/v1/artists/"))) assertTrue(sent.isEmpty());
                else assertEquals(List.of("twc1_catalog_" + platform.apiName()), sent);
                return response(null);
            });
            for (String path : List.of("/v1/albums/" + platform.apiName() + ":1", "/v1/artists/" + platform.apiName() + ":1/tracks",
                    "/v1/account/library/albums", "/v1/playlists/" + platform.apiName() + ":1", "/v1/tracks/" + platform.apiName() + ":1/stream"))
                gateway.requestForPlatform(platform, "GET", path, Map.of(), null);
            gateway.requestForPlatform(platform, "PUT", "/v1/account/library/albums/" + platform.apiName() + ":1", Map.of(), null);
        }
    }

    @Test void responseCannotInstallCredentialForUnrequestedPlatform() {
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) ->
                new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonArray(), new JsonObject(), Map.of("migu", "twc1_wrong")));
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> request(gateway));
        assertEquals(Map.of("soda", "twc1_original"), credentials);
    }

    @Test void updatedHeadersOverrideMetadataAndNeverAppearInResponseToString() {
        JsonObject meta = JsonParser.parseString("{\"caller_credential\":{\"format\":\"tuneweave_credential_v1\",\"platform\":\"soda\",\"value\":\"twc1_meta\"}}").getAsJsonObject();
        var updates = TuneWeaveCredentialUpdates.parse(meta, List.of("soda=twc1_header==, migu=twc1_other"));
        assertEquals(Map.of("soda", "twc1_header==", "migu", "twc1_other"), updates);
        assertFalse(new TuneWeaveApiClient.TuneWeaveResponse(200, meta, meta, updates).toString().contains("twc1"));
    }

    @Test void malformedOrConflictingUpdatesFailWithoutExposingValues() {
        for (String header : List.of("unknown=twc1_secret", "soda=bad-secret", "soda=twc1_", "soda=twc1_secret\n",
                "soda=twc1_a,soda=twc1_secret", "soda=twc1_secret,")) {
            String input = header;
            var error = assertThrows(TuneWeaveApiClient.TuneWeaveException.class,
                    () -> TuneWeaveCredentialUpdates.parse(new JsonObject(), List.of(input)));
            assertFalse(error.toString().contains("secret"));
        }
    }

    @Test void allSevenPlatformReferencesAreStrictAndIndependent() {
        assertEquals(7, TuneWeavePlatform.values().length);
        for (var platform : TuneWeavePlatform.values()) {
            assertSame(platform, TuneWeaveReference.platform(platform.apiName() + ":42"));
            assertSame(platform, TuneWeaveReference.platform("account:favorite_tracks:" + platform.apiName()));
        }
        assertThrows(RuntimeException.class, () -> TuneWeaveReference.platform("unknown:42"));
    }
}
