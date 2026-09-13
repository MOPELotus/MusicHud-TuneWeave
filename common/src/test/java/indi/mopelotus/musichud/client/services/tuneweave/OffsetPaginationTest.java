package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OffsetPaginationTest {
    @Test void pagesPublishBeforeNextRequestAndKeepFirstDuplicateInOrder() {
        List<String> events = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        OffsetPagination.walk(offset -> {
            events.add("request:" + offset);
            return offset == 0 ? response(true, 2, "a", "b") : response(false, 4, "b", "c");
        }, 0, page -> {
            events.add("page:" + page.nextOffset());
            page.items().forEach(item -> refs.add(item.getAsJsonObject().get("ref").getAsString()));
        }, () -> true, 3);
        assertEquals(List.of("request:0", "page:2", "request:2", "page:4"), events);
        assertEquals(List.of("a", "b", "c"), refs);
    }

    @Test void cancellationAfterFirstPagePreventsFurtherNetworkCalls() {
        var active = new AtomicBoolean(true);
        int[] requests = {0};
        assertThrows(CancellationException.class, () -> OffsetPagination.walk(offset -> {
            requests[0]++;
            return response(true, 1, "a");
        }, 0, page -> active.set(false), active::get, 3));
        assertEquals(1, requests[0]);
    }

    @Test void lateResponseIsNotPublishedAfterCancellation() {
        var active = new AtomicBoolean(true);
        assertThrows(CancellationException.class, () -> OffsetPagination.walk(offset -> {
            active.set(false);
            return response(false, 1, "a");
        }, 0, page -> fail("Stale page published"), active::get, 3));
    }

    @Test void malformedOffsetsAndBooleansAreRejectedBeforePublication() {
        for (JsonElement value : List.of(new JsonPrimitive(-1), new JsonPrimitive(0),
                new JsonPrimitive(0.5), new JsonPrimitive(2147483648L), new JsonPrimitive("1"), JsonNull.INSTANCE)) {
            var response = response(true, 1, "a");
            response.meta().getAsJsonObject("pagination").add("next_offset", value);
            assertThrows(TuneWeaveApiClient.TuneWeaveException.class,
                    () -> OffsetPagination.walk(offset -> response, 0, page -> fail("Malformed page published"), () -> true, 3));
        }
        var response = response(false, 1, "a");
        response.meta().getAsJsonObject("pagination").addProperty("has_more", "true");
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> OffsetPagination.loadAll(offset -> response));
    }

    @Test void emptyFinalPageWorksButEmptyContinuationAndInfinitePagesFail() {
        assertTrue(OffsetPagination.loadAll(offset -> response(false, 0)).isEmpty());
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class,
                () -> OffsetPagination.loadAll(offset -> response(true, 1)));
        int[] requests = {0};
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class,
                () -> OffsetPagination.walk(offset -> {
                    requests[0]++;
                    return response(true, offset + 1, "repeated");
                }, 0, page -> {}, () -> true, 2));
        assertEquals(2, requests[0]);
    }

    @Test void malformedDataAndItemBudgetAreRejected() {
        var good = response(false, 0);
        var malformed = new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonPrimitive("not a list"), good.meta());
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> OffsetPagination.loadAll(offset -> malformed));
        JsonArray items = new JsonArray();
        for (int i = 0; i <= OffsetPagination.MAX_ITEMS; i++) items.add(new JsonObject());
        var oversized = new TuneWeaveApiClient.TuneWeaveResponse(200, items, good.meta());
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> OffsetPagination.loadAll(offset -> oversized));
    }

    private static TuneWeaveApiClient.TuneWeaveResponse response(boolean more, int next, String... refs) {
        JsonArray items = new JsonArray();
        for (String ref : refs) {
            JsonObject item = new JsonObject(); item.addProperty("ref", ref); items.add(item);
        }
        JsonObject pagination = new JsonObject();
        pagination.addProperty("has_more", more);
        pagination.addProperty("next_offset", next);
        JsonObject meta = new JsonObject(); meta.add("pagination", pagination);
        return new TuneWeaveApiClient.TuneWeaveResponse(200, items, meta);
    }
}
