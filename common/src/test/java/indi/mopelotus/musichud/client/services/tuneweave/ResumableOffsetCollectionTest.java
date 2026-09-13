package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ResumableOffsetCollectionTest {
    @Test void cacheHitInvalidatedDuringPublicationCannotReturnAsCurrent() {
        var cache = new ResumableOffsetCollection<String, String>();
        cache.load("a", false, offset -> page(false, 1, "one"),
                ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
        assertThrows(CancellationException.class, () -> cache.load("a", false,
                offset -> { throw new AssertionError("Unexpected network request"); },
                ResumableOffsetCollectionTest::reference, x -> x, snapshot -> cache.clear()));
    }

    @Test void publishesBeforeNextRequestAndResumesFailedPageWithoutDuplicates() {
        var cache = new ResumableOffsetCollection<String, String>();
        List<List<String>> shown = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> cache.load("a", false, offset -> {
            if (offset == 0) return page(true, 2, "one", "two");
            assertEquals(List.of(List.of("one", "two")), shown);
            throw new IllegalStateException("offline page two");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> shown.add(snapshot.items())));
        List<Integer> requests = new ArrayList<>();
        var resumed = cache.load("a", false, offset -> {
            requests.add(offset);
            return page(false, 4, "two", "three");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
        assertEquals(List.of(2), requests);
        assertEquals(List.of("one", "two", "three"), resumed.items());
        assertTrue(resumed.complete());
        assertThrows(UnsupportedOperationException.class, () -> resumed.items().add("four"));
        cache.load("a", false, offset -> { fail("Complete cache hit sent a request"); return null; },
                ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
    }

    @Test void refreshAndAccountKeysStartFromZero() {
        var cache = new ResumableOffsetCollection<String, String>();
        for (String account : List.of("a", "b")) {
            assertEquals(List.of(account), cache.load(account, false, offset -> {
                assertEquals(0, offset); return page(false, 1, account);
            }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {}).items());
        }
        assertEquals(List.of("fresh"), cache.load("a", true, offset -> {
            assertEquals(0, offset); return page(false, 1, "fresh");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {}).items());
    }

    @Test void supersededRequestCannotPublishOrOverwriteNewCheckpoint() {
        var cache = new ResumableOffsetCollection<String, String>();
        assertThrows(CancellationException.class, () -> cache.load("a", false, offset -> {
            cache.load("a", true, newer -> page(false, 1, "new"),
                    ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
            return page(false, 1, "old");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> fail("Old page published")));
        assertEquals(List.of("new"), cache.load("a", false, offset -> { throw new AssertionError(); },
                ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {}).items());
    }

    @Test void clearDuringRequestRejectsPageAndNextLoadStartsFresh() {
        var cache = new ResumableOffsetCollection<String, String>();
        assertThrows(CancellationException.class, () -> cache.load("a", false, offset -> {
            cache.clear(); return page(false, 1, "old");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> fail("Cleared page published")));
        cache.load("a", false, offset -> {
            assertEquals(0, offset); return page(false, 1, "new");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
    }

    @Test void expiredAndClockRollbackEntriesAreNotReused() {
        AtomicLong now = new AtomicLong(1000);
        var cache = new ResumableOffsetCollection<String, String>(now::get);
        for (long time : new long[]{1000, 301000, 1000}) {
            now.set(time);
            assertNull(cache.peekFresh("a"));
            cache.load("a", false, offset -> {
                assertEquals(0, offset); return page(false, 1, Long.toString(time));
            }, ResumableOffsetCollectionTest::reference, x -> x,
                    snapshot -> assertEquals(List.of(Long.toString(time)), snapshot.items()));
            assertTrue(cache.peekFresh("a").complete());
        }
    }

    @Test void malformedPageDoesNotAdvanceCheckpoint() {
        var cache = new ResumableOffsetCollection<String, String>();
        assertThrows(RuntimeException.class, () -> cache.load("a", false,
                offset -> page(true, 0, "invalid"), ResumableOffsetCollectionTest::reference, x -> x,
                snapshot -> fail("Malformed page published")));
        cache.load("a", false, offset -> {
            assertEquals(0, offset); return page(false, 1, "valid");
        }, ResumableOffsetCollectionTest::reference, x -> x, snapshot -> {});
    }

    private static String reference(JsonElement item) { return item.getAsJsonObject().get("ref").getAsString(); }
    static TuneWeaveApiClient.TuneWeaveResponse page(boolean more, int offset, String... references) {
        JsonArray data = new JsonArray();
        for (String ref : references) { JsonObject item = new JsonObject(); item.addProperty("ref", ref); data.add(item); }
        JsonObject pagination = new JsonObject(); pagination.addProperty("has_more", more); pagination.addProperty("next_offset", offset);
        JsonObject meta = new JsonObject(); meta.add("pagination", pagination);
        return new TuneWeaveApiClient.TuneWeaveResponse(200, data, meta);
    }
}
