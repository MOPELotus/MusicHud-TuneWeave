package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Shared bounded offset reader. Publishes each validated page before requesting the next. */
final class OffsetPagination {
    static final int MAX_PAGES = 10_000;
    static final int MAX_ITEMS = 100_000;
    record Page(List<JsonElement> items, int nextOffset, boolean complete) {
        Page { items = List.copyOf(items); }
    }

    static List<JsonElement> loadAll(IntFunction<TuneWeaveApiClient.TuneWeaveResponse> request) {
        List<JsonElement> items = new ArrayList<>();
        walk(request, 0, page -> items.addAll(page.items()), () -> true, MAX_PAGES);
        return items;
    }

    static void walk(IntFunction<TuneWeaveApiClient.TuneWeaveResponse> request, int startOffset,
                     Consumer<Page> consumer, BooleanSupplier current, int maxPages) {
        if (startOffset < 0 || maxPages <= 0 || maxPages > MAX_PAGES) {
            throw new IllegalArgumentException("Invalid pagination bounds");
        }
        Set<String> seen = new HashSet<>();
        int offset = startOffset;
        int itemCount = 0;
        for (int pageIndex = 0; pageIndex < maxPages; pageIndex++) {
            requireCurrent(current);
            var response = Objects.requireNonNull(request.apply(offset));
            requireCurrent(current);
            Page parsed = readPage(response, offset);
            List<JsonElement> raw = parsed.items();
            if (raw.size() > MAX_ITEMS - itemCount) throw invalid("Too many pagination items");
            itemCount += raw.size();
            boolean hasMore = !parsed.complete();
            int next = parsed.nextOffset();
            List<JsonElement> unique = new ArrayList<>();
            for (JsonElement value : raw) {
                JsonObject entity = TuneWeaveJson.unwrap(value);
                String ref = TuneWeaveJson.string(entity, "ref", TuneWeaveJson.string(entity, "reference", ""));
                if (ref.isBlank() || seen.add(ref)) unique.add(value);
            }
            requireCurrent(current);
            consumer.accept(new Page(unique, next, !hasMore));
            if (!hasMore) return;
            offset = next;
        }
        throw invalid("Too many pagination pages");
    }

    static Page readPage(TuneWeaveApiClient.TuneWeaveResponse response, int offset) {
        List<JsonElement> raw = pageItems(response.data());
        if (offset < 0 || raw.size() > MAX_ITEMS) throw invalid("Invalid page bounds");
        JsonObject metadata = TuneWeaveJson.object(response.meta().get("pagination"));
        JsonElement more = metadata.get("has_more");
        if (more == null || !more.isJsonPrimitive() || !more.getAsJsonPrimitive().isBoolean()) {
            throw invalid("Invalid pagination has_more");
        }
        boolean hasMore = more.getAsBoolean();
        if (hasMore && raw.isEmpty()) throw invalid("Empty pagination page advertises more items");
        int next;
        try {
            JsonElement value = metadata.get("next_offset");
            if (hasMore && (value == null || value.isJsonNull())) {
                throw invalid("Missing pagination next_offset");
            }
            next = value == null || value.isJsonNull()
                    ? Math.addExact(offset, raw.size()) : exactOffset(value);
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid("Invalid pagination next_offset");
        }
        if (next < offset || (hasMore && next == offset)) throw invalid("Non-advancing pagination offset");
        return new Page(raw, next, !hasMore);
    }

    private static List<JsonElement> pageItems(JsonElement data) {
        if (data != null && data.isJsonArray()) return TuneWeaveJson.elements(data);
        if (data != null && data.isJsonObject()) {
            for (String field : List.of("items", "playlists", "results", "tracks", "episodes", "stations", "parts", "podcasts")) {
                JsonElement items = data.getAsJsonObject().get(field);
                if (items != null && items.isJsonArray()) return TuneWeaveJson.elements(items);
            }
        }
        throw invalid("Pagination response is missing an item array");
    }

    private static int exactOffset(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("Pagination offset must be an integer");
        }
        int offset = value.getAsBigDecimal().intValueExact();
        if (offset < 0) throw invalid("Negative pagination offset");
        return offset;
    }

    private static void requireCurrent(BooleanSupplier current) {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
            throw new CancellationException("Pagination request was cancelled");
        }
    }

    private static TuneWeaveApiClient.TuneWeaveException invalid(String message) {
        return new TuneWeaveApiClient.TuneWeaveException(message, false);
    }
}
