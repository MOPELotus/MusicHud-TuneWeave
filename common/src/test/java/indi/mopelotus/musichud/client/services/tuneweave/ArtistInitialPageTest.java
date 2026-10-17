package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ArtistInitialPageTest {
    @Test void detailLoadsTheFirstTrackPageEvenWhenTheProviderOmitsCounts() {
        List<String> paths = new ArrayList<>();
        var fixture = fixture((base, method, path, query, body, credentials) -> {
            paths.add(path);
            if (path.endsWith("/tracks")) {
                assertEquals("0", query.get("offset")); assertEquals("50", query.get("limit"));
                return response("[{\"ref\":\"netease:1\",\"name\":\"First track\",\"duration_ms\":1000}]");
            }
            return response("{\"ref\":\"netease:artist\",\"name\":\"Artist\"}");
        });
        var artist = fixture.gateway.capture(fixture.mapper.capture(() -> fixture.catalog.loadArtistDetail("netease:artist"))).get();
        assertEquals(2, paths.size());
        assertEquals("netease:1", artist.getMusicDetails().getFirst().getSourceRef());
        assertTrue(fixture.mapper.artist(artist.getId()).getMusicDetails().isEmpty(), "Metadata must not be mutated by the detail load");
    }

    @Test void accountMappingChangeDuringTheFirstPageRejectsTheEntireDetail() {
        AtomicReference<TuneWeaveEntityMapper> mapper = new AtomicReference<>();
        var fixture = fixture((base, method, path, query, body, credentials) -> {
            if (path.endsWith("/tracks")) {
                mapper.get().clear();
                return response("[{\"ref\":\"netease:1\",\"name\":\"Stale track\"}]");
            }
            return response("{\"ref\":\"netease:artist\",\"name\":\"Artist\"}");
        });
        mapper.set(fixture.mapper);
        assertThrows(CancellationException.class, () -> fixture.gateway.capture(fixture.mapper.capture(() -> fixture.catalog.loadArtistDetail("netease:artist"))).get());
    }

    private record Fixture(TuneWeaveGateway gateway, TuneWeaveEntityMapper mapper, TuneWeaveCatalogService catalog) {}
    private static Fixture fixture(TuneWeaveGateway.Transport transport) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        var gateway = new TuneWeaveGateway(config, transport);
        var sessions = new EnumMap<TuneWeavePlatform, TuneWeaveSession>(TuneWeavePlatform.class);
        var mapper = new TuneWeaveEntityMapper(sessions::get);
        var auth = new TuneWeaveAuthenticationService(gateway, sessions);
        return new Fixture(gateway, mapper, new TuneWeaveCatalogService(gateway, mapper, new TuneWeaveAccountService(gateway, auth, mapper)));
    }
    private static TuneWeaveApiClient.TuneWeaveResponse response(String json) {
        return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
    }
}
