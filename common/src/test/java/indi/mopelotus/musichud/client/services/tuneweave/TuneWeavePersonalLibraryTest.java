package indi.mopelotus.musichud.client.services.tuneweave;
import com.google.gson.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TuneWeavePersonalLibraryTest {
    private final TuneWeaveEntityMapper entities = new TuneWeaveEntityMapper(p -> null);
    @Test void unresolvedPurchasesRemainVisibleWithoutInventedPlayableReferences() {
        var raw = JsonParser.parseString("{\"track\":null,\"name\":\"Purchased song\",\"extensions\":{\"goods_id\":\"123\",\"hash\":\"abc\"}}").getAsJsonObject();
        var entry = TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.KUGOU, TuneWeavePersonalLibrary.Kind.PURCHASED_TRACKS, 8, raw);
        assertEquals(8, entry.occurrence()); assertEquals("", entry.reference()); assertNull(entry.resource()); assertEquals("unresolved", entry.status());
    }
    @Test void purchaseOccurrencesAndDigitalKindsDoNotCollapseIntoOrdinaryAlbums() {
        var raw = JsonParser.parseString("{\"digital_album\":{\"ref\":\"migu:123\",\"name\":\"Digital\"}}").getAsJsonObject();
        var first = TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.MIGU, TuneWeavePersonalLibrary.Kind.PURCHASED_ALBUMS, 0, raw);
        var second = TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.MIGU, TuneWeavePersonalLibrary.Kind.PURCHASED_ALBUMS, 1, raw);
        assertNotEquals(first, second); assertEquals("digital_album", first.resourceKind()); assertNull(first.resource());
        raw.add("album", raw.get("digital_album"));
        assertThrows(IllegalArgumentException.class, () -> TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.MIGU, TuneWeavePersonalLibrary.Kind.PURCHASED_ALBUMS, 0, raw));
    }
    @Test void resolvedPurchaseUsesNormalizedAlbumArtworkAndPurchaseFallback() {
        var raw = JsonParser.parseString("""
                {"track":{"ref":"kugou:123","name":"Song","album":{"ref":"kugou:4","cover_url":"http://example.com/album.png"}},
                 "cover_url":"https://example.com/purchase.png"}
                """).getAsJsonObject();
        var entry = TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.KUGOU,
                TuneWeavePersonalLibrary.Kind.PURCHASED_TRACKS, 0, raw);
        assertEquals("https://example.com/album.png", entry.coverUrl());
        raw.getAsJsonObject("track").getAsJsonObject("album").remove("cover_url");
        entry = TuneWeavePersonalLibrary.parse(new TuneWeaveEntityMapper(p -> null), TuneWeavePlatform.KUGOU,
                TuneWeavePersonalLibrary.Kind.PURCHASED_TRACKS, 1, raw);
        assertEquals("https://example.com/purchase.png", entry.coverUrl());
    }
    @Test void foreignReferencesAndInvalidNestedResourcesAreRejected() {
        var foreign = JsonParser.parseString("{\"ref\":\"netease:123\",\"name\":\"wrong\"}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.MIGU, TuneWeavePersonalLibrary.Kind.DIGITAL_ALBUMS, 0, foreign));
        var malformed = JsonParser.parseString("{\"track\":42}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.KUGOU, TuneWeavePersonalLibrary.Kind.PURCHASED_TRACKS, 0, malformed));
    }
    @Test void submissionApprovalDoesNotBecomePublicationOrAnOrdinaryPlaylist() {
        var raw = JsonParser.parseString("{\"playlist_ref\":\"kuwo:123\",\"name\":\"Pending playlist\",\"review_status\":\"approved\",\"published\":null}").getAsJsonObject();
        var entry = TuneWeavePersonalLibrary.parse(entities, TuneWeavePlatform.KUWO, TuneWeavePersonalLibrary.Kind.SUBMISSIONS, 0, raw);
        assertEquals("approved", entry.status()); assertNull(entry.resource()); assertEquals("playlist", entry.resourceKind());
    }
    @Test void digitalSubscriptionUsesItsOwnRouteAndNeverAcceptsOrdinaryAlbums() {
        var methods = new java.util.ArrayList<String>();
        var credential = new java.util.concurrent.atomic.AtomicReference<>("twc1_synthetic");
        var gateway = new TuneWeaveGateway(ExtendedPaginationTest.config(credential), (b, m, p, q, body, credentials) -> {
            assertEquals("/v1/account/library/digital-albums/migu%3A123", p);
            assertNull(body); assertTrue(q.isEmpty()); assertEquals(java.util.List.of("twc1_synthetic"), credentials);
            methods.add(m);
            return new indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveResponse(200, new JsonObject(), new JsonObject());
        });
        var entry = new TuneWeavePersonalLibrary.Entry(0, "migu:123", "digital_album", "Digital", "", "", null);
        TuneWeavePersonalLibrary.setDigitalSubscribed(gateway, entry, true);
        TuneWeavePersonalLibrary.setDigitalSubscribed(gateway, entry, false);
        assertEquals(java.util.List.of("PUT", "DELETE"), methods);
        var old = gateway.capture(() -> { TuneWeavePersonalLibrary.setDigitalSubscribed(gateway, entry, true); return null; });
        credential.set("twc1_replaced");
        assertThrows(java.util.concurrent.CancellationException.class, old::get);
        for (String kind : java.util.List.of("album", "playlist", "unresolved")) {
            var invalid = new TuneWeavePersonalLibrary.Entry(0, "migu:123", kind, "Wrong", "", "", null);
            assertThrows(IllegalArgumentException.class, () -> TuneWeavePersonalLibrary.setDigitalSubscribed(gateway, invalid, true));
        }
        assertEquals(2, methods.size());
    }
}
