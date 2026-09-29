package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SearchPaginationTest {
    @Test void searchUsesProviderContinuationInsteadOfAssumingFiftyItemsPerPage() {
        var gateway = new TuneWeaveGateway(ExtendedPaginationTest.config(new AtomicReference<>("test")),
                (b, m, p, q, body, credentials) -> {
                    assertEquals("50", q.get("limit")); assertEquals("keywords", q.get("q"));
                    return ResumableOffsetCollectionTest.page(true, 10, "netease:one", "netease:two");
                });
        var entities = new TuneWeaveEntityMapper(platform -> null);
        var auth = new TuneWeaveAuthenticationService(gateway, new HashMap<>());
        var catalog = new TuneWeaveCatalogService(gateway, entities, new TuneWeaveAccountService(gateway, auth, entities));
        for (var type : new SearchType[]{SearchType.MUSIC, SearchType.ALBUM, SearchType.ARTIST, SearchType.PLAYLIST, SearchType.RADIO}) {
            var page = catalog.searchPage("keywords", type, 0, TuneWeavePlatform.NETEASE);
            assertEquals(2, page.items().size()); assertTrue(page.hasMore()); assertEquals(10, page.nextOffset());
        }
        assertThrows(IllegalArgumentException.class, () -> catalog.searchPage("keywords", SearchType.MUSIC, -1, TuneWeavePlatform.NETEASE));
        assertThrows(IllegalArgumentException.class, () -> catalog.searchPage("x".repeat(1001), SearchType.MUSIC, 0, TuneWeavePlatform.NETEASE));
    }
    @Test void mixedMiguAlbumKindsKeepOrderIdentityAndProviderContinuation() {
        var data = com.google.gson.JsonParser.parseString("""
                [{"type":"album","data":{"ref":"migu:123","name":"Ordinary"}},
                 {"type":"digital_album","data":{"ref":"migu:123","name":"Digital"}}]
                """).getAsJsonArray();
        var meta = com.google.gson.JsonParser.parseString("{\"pagination\":{\"has_more\":true,\"next_offset\":17}}").getAsJsonObject();
        var gateway = new TuneWeaveGateway(ExtendedPaginationTest.config(new AtomicReference<>("test")),
                (b, m, p, q, body, credentials) -> {
                    assertTrue(credentials.isEmpty());
                    return new indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveResponse(200, data, meta);
                });
        var entities = new TuneWeaveEntityMapper(platform -> null);
        var auth = new TuneWeaveAuthenticationService(gateway, new HashMap<>());
        var catalog = new TuneWeaveCatalogService(gateway, entities, new TuneWeaveAccountService(gateway, auth, entities));
        var page = catalog.searchPage("keywords", SearchType.ALBUM, 0, TuneWeavePlatform.MIGU);
        assertEquals(17, page.nextOffset()); assertTrue(page.hasMore());
        var ordinary = assertInstanceOf(indi.mopelotus.musichud.beans.music.Album.class, page.items().get(0));
        var digital = assertInstanceOf(TuneWeavePersonalLibrary.Entry.class, page.items().get(1));
        assertEquals(ordinary.getSourceRef(), digital.reference());
        assertEquals("digital_album", digital.resourceKind()); assertEquals(TuneWeavePlatform.MIGU, digital.platform());
        assertSame(ordinary, entities.album(ordinary.getId()));
        data.get(1).getAsJsonObject().getAsJsonObject("data").addProperty("ref", "kuwo:123");
        assertThrows(IllegalArgumentException.class, () -> catalog.searchPage("keywords", SearchType.ALBUM, 0, TuneWeavePlatform.MIGU));
    }
}
