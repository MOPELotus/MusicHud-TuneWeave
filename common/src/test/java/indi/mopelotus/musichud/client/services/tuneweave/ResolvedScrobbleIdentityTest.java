package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedScrobbleIdentityTest {
    @Test void uniAndCloudUseActualReferenceQualityAndDuration() {
        for (boolean cloud : new boolean[]{false, true}) {
            var requested = MusicDetail.fromTuneWeave(1, "qq:original", "track", "Song", 90_000, Album.NONE, List.of());
            requested.setCloudSource(cloud); requested.setClientHostedUni(!cloud);
            var resource = new MusicResourceInfo(1, "https://example.org/audio", 320000, 100, FormatType.FLAC, "", Fee.UNSET, 60_000);
            resource.setQualityMetadata(Quality.LOSSLESS, Quality.VIVID);
            assertFalse(TuneWeavePlaybackService.scrobbleEligible(requested, 31_000, resource));
            resource.setResolvedTrackReference("netease:actual");
            var service = service((base, method, path, query, body, credentials) -> {
                if (method.equals("GET")) return response("[{\"platform\":\"netease\",\"registered\":true,\"capabilities\":[\"scrobble_write\"]}]");
                assertEquals("/v1/tracks/netease%3Aactual/scrobble", path);
                assertEquals("vivid", body.getAsJsonObject().get("quality").getAsString());
                assertEquals(60_000, body.getAsJsonObject().get("duration_ms").getAsLong());
                return response("{\"accepted\":true,\"track_ref\":\"netease:actual\"}");
            });
            assertTrue(service.scrobble(requested, 31_000, resource, Quality.LOSSLESS));
            service.prepareScrobble(requested).accept(31_000L, resource);
            resource.setResolvedTrackReference("qq:actual");
            assertFalse(TuneWeavePlaybackService.scrobbleEligible(requested, 31_000, resource));
        }
    }

    @Test void resolverRetainsActualIdentityAndCorrectlyMapsHighQuality() {
        var requested = MusicDetail.fromTuneWeave(1, "qq:original", "track", "Song", 90_000, Album.NONE, List.of());
        requested.setClientHostedUni(true);
        var service = service((base, method, path, query, body, credentials) -> {
            assertEquals("/v1/uni/items/stream", path);
            return response("{\"stream\":{\"url\":\"https://example.org/audio\",\"bitrate\":320000,\"actual_quality\":\"high\",\"resolved_track\":\"netease:actual\"}}");
        });
        var resource = service.resolve(requested, Quality.LOSSLESS);
        assertEquals("netease:actual", resource.getResolvedTrackReference());
        assertEquals(Quality.EX_HIGH, resource.getActualQuality());
        assertThrows(IllegalArgumentException.class, () -> resource.setResolvedTrackReference("http://private/token"));
        assertThrows(IllegalArgumentException.class, () -> resource.setResolvedTrackReference("netease:bad\nref"));
        assertThrows(IllegalArgumentException.class, () -> resource.setResolvedTrackReference("netease:" + "x".repeat(513)));
    }

    private static TuneWeavePlaybackService service(TuneWeaveGateway.Transport transport) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "qq";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        return new TuneWeavePlaybackService(new TuneWeaveGateway(config, transport));
    }
    private static TuneWeaveApiClient.TuneWeaveResponse response(String value) {
        return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(value), new JsonObject());
    }
}
