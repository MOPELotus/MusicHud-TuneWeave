package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalUniItemReuseTest {
    @Test void mixesScopedLocalTracksAndMissingRunsInCallerOrder() {
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
        sessions.put(TuneWeavePlatform.NETEASE, session(TuneWeavePlatform.NETEASE, "a"));
        sessions.put(TuneWeavePlatform.QQ, session(TuneWeavePlatform.QQ, "q"));
        var entities = new TuneWeaveEntityMapper(sessions::get);
        entities.cacheTrack(track(1, "netease:1", "Local A"));
        entities.cacheTrack(track(2, "qq:2", "Local Q"));
        List<List<String>> requests = new ArrayList<>();
        var service = new TuneWeaveUniPlaylistService(gateway(requests), entities, new LocalUniPlaylistStore());
        var result = service.materializeRequestedItems(List.of("netease:1", "netease:3", "netease:4", "qq:2"), "track");
        assertEquals(List.of(List.of("netease:3", "netease:4")), requests);
        assertEquals(List.of("netease:1", "netease:3", "netease:4", "qq:2"),
                result.stream().map(item -> item.get("source_ref").getAsString()).toList());
        assertEquals("Local Q", result.get(3).getAsJsonObject("snapshot").get("title").getAsString());
        for (int i = 0; i < result.size(); i++) assertEquals(i, result.get(i).get("position").getAsInt());
        sessions.put(TuneWeavePlatform.NETEASE, session(TuneWeavePlatform.NETEASE, "b"));
        service.materializeRequestedItems(List.of("netease:1", "qq:2"), "track");
        assertEquals(List.of("netease:1"), requests.getLast());
    }

    @Test void completeLocalItemsNeedNoTransportAndInvalidReferencesAreRejected() {
        var entities = new TuneWeaveEntityMapper(platform -> session(platform, "a"));
        entities.cacheTrack(track(1, "netease:1", "Local"));
        List<List<String>> requests = new ArrayList<>();
        var service = new TuneWeaveUniPlaylistService(gateway(requests), entities, new LocalUniPlaylistStore());
        assertEquals(2, service.materializeRequestedItems(List.of("netease:1", "netease:1"), "track").size());
        assertTrue(requests.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.materializeRequestedItems(List.of(""), "track"));
    }

    private static TuneWeaveGateway gateway(List<List<String>> requests) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch(method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        return new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
            assertEquals("/v1/uni/materialize/items", path);
            List<String> refs = new ArrayList<>();
            JsonArray items = new JsonArray();
            for (JsonElement value : body.getAsJsonObject().getAsJsonArray("items")) {
                String ref = value.getAsJsonObject().get("ref").getAsString();
                refs.add(ref);
                items.add(TuneWeaveUniPlaylistService.materializeLocalTrack(track(3, ref, "Remote"), 0));
            }
            requests.add(refs);
            JsonObject data = new JsonObject(); data.add("items", items);
            return new TuneWeaveApiClient.TuneWeaveResponse(200, data, new JsonObject());
        });
    }
    private static TuneWeaveSession session(TuneWeavePlatform platform, String id) { return new TuneWeaveSession(platform, id, id, "", true); }
    private static MusicDetail track(int id, String reference, String name) {
        return MusicDetail.fromTuneWeave(id, reference, "track", name, 90_000, Album.NONE, List.of());
    }
}
