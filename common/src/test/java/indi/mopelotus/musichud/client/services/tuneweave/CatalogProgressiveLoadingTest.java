package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CatalogProgressiveLoadingTest {
    @Test void playlistAndAlbumPublishFirstPageBeforeSecondAndResumeAfterFailure() {
        for (boolean album : List.of(false, true)) {
            List<Integer> offsets = new ArrayList<>();
            List<MusicCollection> snapshots = new ArrayList<>();
            AtomicBoolean failSecond = new AtomicBoolean(true);
            String reference = "netease:collection";
            var catalog = catalog((base, method, path, query, body, credentials) -> {
                if (!path.endsWith("/items") && !path.endsWith("/tracks")) {
                    JsonObject metadata = new JsonObject();
                    metadata.addProperty("ref", reference); metadata.addProperty("name", "Collection");
                    metadata.addProperty("track_count", 2);
                    return new TuneWeaveApiClient.TuneWeaveResponse(200, metadata, new JsonObject());
                }
                int offset = Integer.parseInt(query.get("offset"));
                offsets.add(offset);
                if (offset == 1) {
                    assertFalse(snapshots.isEmpty());
                    assertEquals(1, snapshots.getFirst().getMusicDetails().size());
                    if (failSecond.get()) throw new IllegalStateException("offline");
                }
                var page = ResumableOffsetCollectionTest.page(offset == 0, offset + 1, "netease:" + (offset + 1));
                page.data().getAsJsonArray().get(0).getAsJsonObject().addProperty("duration_ms", 90_000);
                return page;
            });
            assertThrows(IllegalStateException.class, () -> {
                if (album) catalog.loadAlbumDetail(reference, false, snapshots::add);
                else catalog.loadPlaylistDetail(reference, false, snapshots::add);
            });
            failSecond.set(false);
            MusicCollection completed = album ? catalog.loadKnownAlbumForImport(reference)
                    : catalog.loadKnownPlaylistForImport(reference);
            assertEquals(List.of(0, 1, 1), offsets);
            assertEquals(2, completed.getMusicDetails().size());
            {
                MusicCollection cached = album ? catalog.loadKnownAlbumForImport(reference) : catalog.loadKnownPlaylistForImport(reference);
                assertEquals(2, cached.getMusicDetails().size());
                assertEquals(List.of(0, 1, 1), offsets);
                catalog.clear();
                assertNull(album ? catalog.loadKnownAlbumForImport(reference) : catalog.loadKnownPlaylistForImport(reference));
            }
            assertEquals(1, snapshots.getFirst().getMusicDetails().size(), "Later pages must not mutate an earlier snapshot");
            if (album) catalog.loadAlbumDetail(reference, true, snapshots::add);
            else catalog.loadPlaylistDetail(reference, true, snapshots::add);
            assertEquals(List.of(0, 1, 1, 0, 1), offsets);
        }
    }

    private static TuneWeaveCatalogService catalog(TuneWeaveGateway.Transport transport) {
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
        return new TuneWeaveCatalogService(gateway, mapper, new TuneWeaveAccountService(gateway, auth, mapper));
    }
}
