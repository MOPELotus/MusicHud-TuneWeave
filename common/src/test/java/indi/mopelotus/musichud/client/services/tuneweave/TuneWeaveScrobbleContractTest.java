package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveScrobbleContractTest {
    private static final String CAPABILITIES = "[{\"platform\":\"netease\",\"registered\":true,\"capabilities\":[\"scrobble_write\"]}]";

    @Test void submitsActualQualityAndPlayedTimeWithCapturedAccount() {
        var credential = new AtomicReference<>("account-a");
        List<String> requests = new ArrayList<>();
        var service = service(credential, (base, method, path, query, body, credentials) -> {
            requests.add(method);
            assertEquals(List.of("account-a"), credentials);
            if (method.equals("GET")) {
                assertEquals("/v1/capabilities", path);
                assertEquals(Map.of("platform", "netease"), query);
                return response(CAPABILITIES);
            }
            assertEquals("/v1/tracks/netease%3A42/scrobble", path);
            assertEquals(31_000, body.getAsJsonObject().get("played_ms").getAsLong());
            assertEquals(90_000, body.getAsJsonObject().get("duration_ms").getAsLong());
            assertEquals("higher", body.getAsJsonObject().get("quality").getAsString());
            assertEquals(320_000, body.getAsJsonObject().get("bitrate").getAsInt());
            return response("{\"accepted\":true,\"track_ref\":\"netease:42\"}");
        });
        assertTrue(service.scrobble(track(), 31_000, resource(), Quality.HIGHER));
        assertEquals(List.of("GET", "POST"), requests);
        var submit = service.prepareScrobble(track());
        credential.set("account-b");
        assertThrows(CancellationException.class, () -> submit.accept(31_000L, resource()));
        assertEquals(2, requests.size());
    }

    @Test void requiresCapabilityAndStrictAcknowledgement() {
        for (String malformed : List.of("null", "{}", "[]", "[1]",
                "[{\"platform\":{},\"registered\":true}]",
                "[{\"platform\":\"netease\",\"registered\":\"true\",\"capabilities\":[\"scrobble_write\"]}]")) {
            assertFalse(TuneWeavePlaybackService.supportsScrobble(JsonParser.parseString(malformed)));
        }
        assertTrue(TuneWeavePlaybackService.supportsScrobble(JsonParser.parseString(CAPABILITIES)));
        for (String acknowledgement : List.of("{}", "{\"accepted\":false}",
                "{\"accepted\":\"true\",\"track_ref\":\"netease:42\"}",
                "{\"accepted\":true,\"track_ref\":{}}",
                "{\"accepted\":true,\"track_ref\":\"netease:43\"}")) {
            var service = service(new AtomicReference<>("a"), (b, m, p, q, body, c) -> response(m.equals("GET") ? CAPABILITIES : acknowledgement));
            assertFalse(service.scrobble(track(), 31_000, resource(), Quality.HIGHER));
        }
        var unavailable = service(new AtomicReference<>("a"), (b, m, p, q, body, c) -> {
            assertEquals("GET", m);
            return response("[]");
        });
        assertFalse(unavailable.scrobble(track(), 31_000, resource(), Quality.HIGHER));
    }

    private static TuneWeavePlaybackService service(AtomicReference<String> credential, TuneWeaveGateway.Transport transport) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch(method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> credential.get();
                    default -> throw new AssertionError(method.getName());
                });
        return new TuneWeavePlaybackService(new TuneWeaveGateway(config, transport));
    }
    private static TuneWeaveApiClient.TuneWeaveResponse response(String json) {
        return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
    }
    private static MusicDetail track() { return MusicDetail.fromTuneWeave(42, "netease:42", "track", "Test", 90_000, Album.NONE, List.of()); }
    private static MusicResourceInfo resource() {
        var resource = new MusicResourceInfo(42, "https://example.invalid/audio", 320_000, 100, FormatType.MP3, "", Fee.UNSET, 90_000);
        resource.setQualityMetadata(Quality.LOSSLESS, Quality.HIGHER);
        return resource;
    }
}
