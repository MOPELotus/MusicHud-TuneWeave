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
}
