package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FavoriteIntelligenceContractTest {
    @Test void capabilityIsRequiredBeforeRequestingRecommendationData() {
        List<String> requests = new ArrayList<>(); var fixture = fixture(false, "netease:next", requests);
        assertThrows(IllegalStateException.class, () -> fixture.service.loadFavoriteIntelligence(fixture.playlist, ""));
        assertEquals(List.of("/v1/capabilities"), requests);
    }

    @Test void requestCarriesSeedStartAndCountAndKeepsUniqueNormalizedTracks() {
        List<String> requests = new ArrayList<>(); var fixture = fixture(true, "netease:next", requests);
        var tracks = fixture.service.loadFavoriteIntelligence(fixture.playlist, "netease:start");
        assertEquals(1, tracks.size()); assertEquals("netease:next", tracks.getFirst().getSourceRef());
        assertEquals(List.of("/v1/capabilities", "/v1/account/favorites/tracks", "/v1/account/favorites/tracks/intelligence"), requests);
    }

    @Test void wrongPlatformStartOrRecommendationIsRejected() {
        List<String> requests = new ArrayList<>(); var fixture = fixture(true, "qq:wrong", requests);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.loadFavoriteIntelligence(fixture.playlist, "qq:wrong"));
        assertTrue(requests.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.loadFavoriteIntelligence(fixture.playlist, "netease:start"));
    }

    private record Fixture(TuneWeaveAccountService service, Playlist playlist) {}
    private static Fixture fixture(boolean supported, String recommended, List<String> requests) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
            if (path.equals("/v1/account/favorites/playlist")) return response("{\"ref\":\"netease:favorites\",\"name\":\"Favorites\"}");
            if (path.equals("/v1/account/playlists")) return ResumableOffsetCollectionTest.page(false, 0);
            requests.add(path);
            if (path.equals("/v1/capabilities")) return response(supported
                    ? "[{\"platform\":\"netease\",\"registered\":true,\"capabilities\":[\"favorite_intelligence\"]}]" : "[]");
            if (path.equals("/v1/account/favorites/tracks")) return response("[{\"ref\":\"netease:seed\",\"duration_ms\":1000}]");
            assertEquals("netease:seed", query.get("seed")); assertEquals("netease:start", query.get("start")); assertEquals("1", query.get("count"));
            String item = "{\"track\":{\"ref\":\"" + recommended + "\",\"duration_ms\":1000}}";
            return response("{\"items\":[" + item + "," + item + "]}");
        });
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
        sessions.put(TuneWeavePlatform.NETEASE, new TuneWeaveSession(TuneWeavePlatform.NETEASE, "1", "User", "", true));
        var mapper = new TuneWeaveEntityMapper(sessions::get);
        var service = new TuneWeaveAccountService(gateway, new TuneWeaveAuthenticationService(gateway, sessions), mapper);
        return new Fixture(service, service.loadPlaylists(TuneWeavePlatform.NETEASE).getLikeList());
    }
    private static TuneWeaveApiClient.TuneWeaveResponse response(String json) {
        return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
    }
}
