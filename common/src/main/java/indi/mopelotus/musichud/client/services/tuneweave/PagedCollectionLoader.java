package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** Shared scoped checkpoints for cloud, video and program collections, including opaque radio cursors. */
final class PagedCollectionLoader {
    private record Key(AccountScope scope, String path, Map<String, String> query, boolean cursor) {
        Key { query = Map.copyOf(query); }
    }
    private static final class CursorState {
        final Map<Integer, Map<String, String>> positions = new HashMap<>(Map.of(0, Map.of()));
        final Map<Map<String, String>, Integer> seen = new HashMap<>();
    }
    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final ResumableOffsetCollection<Key, JsonElement> pages = new ResumableOffsetCollection<>();
    private final Cache<Key, CursorState> cursors = cache();
    private final Cache<Key, JsonObject> metadata = cache();
    private static <T> Cache<Key, T> cache() {
        return CacheBuilder.newBuilder().maximumSize(50).expireAfterWrite(5, TimeUnit.MINUTES).build();
    }
    PagedCollectionLoader(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities) {
        this.gateway = gateway; this.entities = entities;
    }
    void clear() { pages.clear(); cursors.invalidateAll(); metadata.invalidateAll(); }

    <T> List<T> load(TuneWeavePlatform platform, String path, Map<String, String> query, boolean refresh,
                     boolean cursor, Function<JsonElement, T> mapper, BiConsumer<List<T>, JsonObject> progress) {
        return gateway.capture(entities.capture(() -> loadInScope(platform, path, query, refresh, cursor, mapper, progress))).get();
    }

    private <T> List<T> loadInScope(TuneWeavePlatform platform, String path, Map<String, String> baseQuery, boolean refresh,
                                   boolean cursor, Function<JsonElement, T> mapper, BiConsumer<List<T>, JsonObject> progress) {
        Key key = new Key(entities.accountScope(platform), path, baseQuery, cursor);
        if (refresh || pages.peekFresh(key) == null) { cursors.invalidate(key); metadata.invalidate(key); }
        CursorState positions = cursors.asMap().computeIfAbsent(key, ignored -> new CursorState());
        var result = pages.load(key, refresh, offset -> {
            Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("limit", "100");
            if (cursor) {
                Map<String, String> position;
                synchronized (positions) { position = positions.positions.get(offset); }
                if (position == null) throw new IllegalArgumentException("Missing radio cursor checkpoint");
                query.putAll(position);
            } else query.put("offset", Integer.toString(offset));
            var response = gateway.requestForPlatform(platform, "GET", path, query, null);
            if (offset == 0) metadata.put(key, response.meta().deepCopy());
            if (!cursor) return response;
            JsonObject meta = response.meta().deepCopy();
            JsonObject pagination = TuneWeaveJson.object(meta.get("pagination"));
            JsonElement more = pagination.get("has_more");
            if (more == null || !more.isJsonPrimitive() || !more.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Invalid radio pagination flag");
            if (more.getAsBoolean()) {
                JsonObject next = TuneWeaveJson.object(TuneWeaveJson.object(pagination.get("extensions")).get("next_cursor"));
                Map<String, String> position = Map.of("last_id", cursorField(next, "id"), "score", cursorField(next, "score"));
                synchronized (positions) {
                    Integer previous = positions.seen.get(position);
                    if (previous != null && previous != offset + 1) throw new IllegalArgumentException("Cyclic radio cursor");
                    if (positions.positions.size() >= OffsetPagination.MAX_PAGES) throw new IllegalArgumentException("Too many radio pages");
                    positions.seen.put(position, offset + 1);
                    positions.positions.put(offset + 1, position);
                }
            }
            pagination.addProperty("next_offset", offset + 1);
            return new TuneWeaveApiClient.TuneWeaveResponse(response.statusCode(), response.data(), meta);
        }, raw -> { Objects.requireNonNull(mapper.apply(raw)); return raw.deepCopy(); }, PagedCollectionLoader::identity, snapshot -> {
            JsonObject meta = metadata.getIfPresent(key);
            progress.accept(snapshot.items().stream().map(mapper).toList(), meta == null ? new JsonObject() : meta.deepCopy());
        });
        return result.items().stream().map(mapper).toList();
    }

    private static String cursorField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid radio cursor field");
        String value = element.getAsString();
        if (value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid radio cursor field");
        return value;
    }

    private static String identity(JsonElement element) {
        JsonObject value = TuneWeaveJson.unwrap(element);
        String ref = TuneWeaveJson.string(value, "ref", TuneWeaveJson.string(value, "reference", ""));
        if (ref.isBlank() || ref.length() > 512) throw new IllegalArgumentException("Missing collection item identity");
        return ref;
    }
}
