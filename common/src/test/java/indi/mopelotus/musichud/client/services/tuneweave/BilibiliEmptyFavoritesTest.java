package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class BilibiliEmptyFavoritesTest {
    @Test void emptyAccountUsesItsOwnScopeAndRefreshFindsNewRealFolder() {
        AtomicBoolean created = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
            calls.add(path); assertEquals("GET", method);
            assertFalse(path.contains("account%3A"));
            if (path.endsWith("/playlists/created")) {
                assertEquals("bilibili", query.get("platform"));
                return created.get() ? ResumableOffsetCollectionTest.page(false, 1, "bilibili:favorite:42")
                        : ResumableOffsetCollectionTest.page(false, 0);
            }
            if (path.endsWith("/items")) return ResumableOffsetCollectionTest.page(false, 0);
            assertTrue(path.contains("bilibili"));
            JsonObject folder = new JsonObject(); folder.addProperty("ref", "bilibili:favorite:42");
            return new TuneWeaveApiClient.TuneWeaveResponse(200, folder, new JsonObject());
        });
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
        sessions.put(TuneWeavePlatform.BILIBILI, new TuneWeaveSession(TuneWeavePlatform.BILIBILI, "1", "User", "", true));
        var entities = new TuneWeaveEntityMapper(sessions::get);
        var account = new TuneWeaveAccountService(gateway, new TuneWeaveAuthenticationService(gateway, sessions), entities);
        var catalog = new TuneWeaveCatalogService(gateway, entities, account);
        var placeholder = account.loadPlaylists(TuneWeavePlatform.BILIBILI).getLikeList();
        assertTrue(account.isFavoritePlaylist(placeholder));
        assertFalse(account.supportsFavoriteIntelligence(placeholder));
        assertSame(placeholder, entities.playlist(placeholder.getId()));
        assertTrue(catalog.loadPlaylistDetail(placeholder.getId()).getMusicDetails().isEmpty());
        assertTrue(catalog.loadKnownPlaylistForImport(placeholder.getSourceRef()).getMusicDetails().isEmpty());
        assertEquals(1, calls.size());
        var mutations = new TuneWeavePlaylistService(gateway, entities, catalog);
        assertThrows(IllegalArgumentException.class, () -> mutations.delete(placeholder));
        assertThrows(IllegalArgumentException.class, () -> mutations.setPlaylistSubscribed(placeholder, true));
        assertThrows(IllegalArgumentException.class, () -> mutations.reorderTracks(placeholder, List.of()));
        assertEquals(1, calls.size());
        created.set(true);
        var actual = catalog.loadPlaylistDetail(placeholder.getSourceRef(), true, ignored -> {});
        assertEquals("bilibili:favorite:42", actual.getSourceRef());
        assertEquals(4, calls.size());
        sessions.put(TuneWeavePlatform.BILIBILI, new TuneWeaveSession(TuneWeavePlatform.BILIBILI, "2", "Other", "", true));
        assertNull(entities.playlist(placeholder.getId()));
    }

    @Test void syntheticReferencesHaveStrictPlatformSuffixes() {
        for (String prefix : List.of("account:favorite_tracks:", "account:favorites:")) {
            assertEquals(TuneWeavePlatform.BILIBILI, TuneWeaveReference.platform(prefix + "bilibili"));
            assertEquals(TuneWeavePlatform.BILIBILI, TuneWeaveReference.platformOrDefault(prefix + "bilibili", TuneWeavePlatform.NETEASE));
        }
        assertEquals(TuneWeavePlatform.QQ, TuneWeaveReference.platform("account:favorite_tracks:qq"));
        for (String suffix : List.of("", "qq:netease", "unknown", "qq "))
            assertThrows(IllegalArgumentException.class, () -> TuneWeaveReference.platform("account:favorite_tracks:" + suffix));
    }
}
