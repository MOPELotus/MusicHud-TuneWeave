package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtistSubscriptionRoutingTest {
    @Test void followingAndUnfollowingArtistsNeverMutateTheAlbumLibrary() {
        List<String> requests = new ArrayList<>();
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
            requests.add(method + " " + path);
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonNull.INSTANCE, new JsonObject());
        });
        var sessions = new EnumMap<TuneWeavePlatform, TuneWeaveSession>(TuneWeavePlatform.class);
        var mapper = new TuneWeaveEntityMapper(sessions::get);
        var auth = new TuneWeaveAuthenticationService(gateway, sessions);
        var catalog = new TuneWeaveCatalogService(gateway, mapper, new TuneWeaveAccountService(gateway, auth, mapper));
        var service = new TuneWeavePlaylistService(gateway, mapper, catalog);
        for (var platform : TuneWeavePlatform.values()) {
            String ref = platform.apiName() + ":42";
            JsonObject data = new JsonObject(); data.addProperty("ref", ref); data.addProperty("name", "Fixture");
            var artist = mapper.toArtist(platform, data);
            service.setArtistSubscribed(artist, true); service.setArtistSubscribed(artist, false);
            String path = "/v1/account/following/artists/" + TuneWeaveApiClient.encodePathSegment(ref);
            assertEquals("PUT " + path, requests.get(requests.size() - 2));
            assertEquals("DELETE " + path, requests.getLast());
        }
        assertEquals(14, requests.size());
        assertTrue(requests.stream().noneMatch(path -> path.contains("library/albums")));
    }
}
