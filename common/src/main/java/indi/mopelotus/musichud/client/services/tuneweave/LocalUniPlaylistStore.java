package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.utils.JsonUtil;
import indi.mopelotus.musichud.utils.collections.ItemMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Client-owned, credential-free storage for TuneWeave Uni Playlist V1 documents. */
final class LocalUniPlaylistStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ITEMS = 100_000;
    private static final String LOCAL_PREFIX = "local:";
    private static final Set<String> DOCUMENT_KEYS = Set.of(
            "format", "id", "name", "description", "item_count", "created_at_ms",
            "updated_at_ms", "items", "extensions");
    private static final Set<String> ITEM_KEYS = Set.of(
            "id", "position", "kind", "source_ref", "snapshot", "added_at_ms", "extensions");
    private static final Set<String> ITEM_EXTENSION_KEYS = Set.of(
            "import_source_index", "import_source_ref", "import_source_type", "imported_from_item_id");
    private static final Set<String> SNAPSHOT_KEYS = Set.of(
            "title", "artists", "album", "duration_ms", "isrc", "cover_url", "version_tags", "extensions");
    private static final Set<String> SNAPSHOT_EXTENSION_KEYS = Set.of(
            "canonical_ref", "playable", "available_qualities", "mv_ref", "video_kind", "published_at",
            "podcast_ref", "audio_ref", "serial_number", "description", "category", "region",
            "current_program", "has_direct_stream");

    private JsonObject database;
    private Path loadedPath;

    synchronized List<JsonObject> list() {
        return ItemMapping.map("local playlist documents", database().getAsJsonArray("playlists"),
                value -> normalizeDocument(value.getAsJsonObject()));
    }

    synchronized JsonObject create(String name, String description) {
        long now = System.currentTimeMillis();
        JsonObject document = new JsonObject();
        document.addProperty("format", "tuneweave_uni_playlist_v1");
        document.addProperty("id", newId("pl_"));
        document.addProperty("name", requiredName(name));
        document.addProperty("description", normalizedDescription(description));
        document.addProperty("item_count", 0);
        document.addProperty("created_at_ms", now);
        document.addProperty("updated_at_ms", now);
        document.add("items", new JsonArray());
        JsonObject extensions = new JsonObject();
        extensions.addProperty("duplicates_preserved", true);
        document.add("extensions", extensions);
        database().getAsJsonArray("playlists").add(document);
        save();
        return normalizeDocument(document);
    }

    synchronized JsonObject update(String reference, String name, String description) {
        JsonObject document = find(reference);
        if (name != null) document.addProperty("name", requiredName(name));
        if (description != null) document.addProperty("description", normalizedDescription(description));
        document.addProperty("updated_at_ms", System.currentTimeMillis());
        save();
        return normalizeDocument(document);
    }

    synchronized void delete(String reference) {
        JsonObject document = find(reference);
        database().getAsJsonArray("playlists").remove(document);
        save();
    }

    synchronized List<JsonObject> items(String reference) {
        return ItemMapping.map("local playlist items", normalizeDocument(find(reference)).getAsJsonArray("items"),
                value -> value.getAsJsonObject().deepCopy());
    }

    synchronized JsonObject append(String reference, List<JsonObject> values) {
        JsonObject document = find(reference);
        if (values == null || values.isEmpty()) return normalizeDocument(document);
        JsonArray items = document.getAsJsonArray("items");
        var valid = ItemMapping.map("appended playlist items", values, raw -> normalizeItem(raw, 0, false));
        if ((long) items.size() + valid.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("A local playlist cannot contain more than 100000 items");
        }
        for (JsonObject item : valid) {
            item.addProperty("id", newId("item_"));
            item.addProperty("position", items.size());
            item.addProperty("added_at_ms", System.currentTimeMillis());
            items.add(item);
        }
        touch(document, items.size());
        save();
        return normalizeDocument(document);
    }

    synchronized void removeItem(String reference, String itemId) {
        JsonObject document = find(reference);
        JsonArray items = document.getAsJsonArray("items");
        for (JsonObject item : validStoredItems(items)) {
            if (itemId.equals(item.get("id").getAsString())) {
                items.remove(item);
                resetPositions(items);
                touch(document, items.size());
                save();
                return;
            }
        }
        throw new IllegalArgumentException("Local playlist item was not found");
    }

    synchronized void reorder(String reference, List<String> itemIds) {
        JsonObject document = find(reference);
        JsonArray current = document.getAsJsonArray("items");
        var visible = validStoredItems(current);
        if (itemIds == null || itemIds.size() != visible.size() || new HashSet<>(itemIds).size() != visible.size()) {
            throw new IllegalArgumentException("The submitted order must contain every visible playlist item exactly once");
        }
        var byId = new java.util.HashMap<String, JsonObject>();
        visible.forEach(item -> byId.put(item.get("id").getAsString(), item));
        JsonArray reordered = new JsonArray();
        for (String itemId : itemIds) {
            JsonObject match = byId.get(itemId);
            if (match == null) throw new IllegalArgumentException("The submitted order contains an unknown item");
            reordered.add(match);
        }
        // Keep unreadable user records in storage even though they are absent from the view.
        var identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<JsonElement, Boolean>());
        identities.addAll(visible);
        for (JsonElement item : current) if (!identities.contains(item)) reordered.add(item);
        resetPositions(reordered);
        document.add("items", reordered);
        touch(document, reordered.size());
        save();
    }

    synchronized JsonObject exportDocument(String reference) {
        return normalizeDocument(find(reference));
    }

    synchronized JsonObject importDocument(JsonObject source) {
        JsonObject document = normalizeDocument(source);
        Set<String> ids = new HashSet<>();
        list().forEach(value -> ids.add(value.get("id").getAsString()));
        if (ids.contains(document.get("id").getAsString())) {
            document.addProperty("id", newId("pl_"));
        }
        document.addProperty("updated_at_ms", System.currentTimeMillis());
        database().getAsJsonArray("playlists").add(document);
        save();
        return normalizeDocument(document);
    }

    private JsonObject database() {
        Path path = path();
        if (database != null && path.equals(loadedPath)) return database;
        loadedPath = path;
        if (!Files.isRegularFile(path)) {
            database = emptyDatabase();
            return database;
        }
        try {
            JsonObject loaded = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!loaded.has("schema_version") || loaded.get("schema_version").getAsInt() != SCHEMA_VERSION
                    || !loaded.has("playlists") || !loaded.get("playlists").isJsonArray()) {
                throw new IllegalStateException("Unsupported local playlist database format");
            }
            // Reads expose validated copies. Keeping the raw backing records prevents
            // an edit to a healthy playlist from deleting unrelated unreadable user data.
            database = loaded;
            return database;
        } catch (RuntimeException | IOException error) {
            throw new IllegalStateException("Failed to read the local playlist database", error);
        }
    }

    private void save() {
        Path target = path();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, JsonUtil.gson.toJson(database), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save the local playlist database", error);
        }
    }

    private JsonObject find(String reference) {
        String id = id(reference);
        var matches = ItemMapping.map("local playlist lookup", database().getAsJsonArray("playlists"), value -> {
            JsonObject document = value.getAsJsonObject();
            if (!id.equals(requiredString(document, "id"))) return null;
            normalizeDocument(document);
            return document;
        });
        if (!matches.isEmpty()) return matches.getFirst();
        throw new IllegalArgumentException("Local playlist was not found");
    }

    private static List<JsonObject> validStoredItems(JsonArray items) {
        Set<String> ids = new HashSet<>();
        return ItemMapping.map("stored playlist items", items, value -> {
            JsonObject raw = value.getAsJsonObject();
            JsonObject normalized = normalizeItem(raw, 0, true);
            return ids.add(normalized.get("id").getAsString()) ? raw : null;
        });
    }

    private static JsonObject normalizeDocument(JsonObject source) {
        requireOnlyKeys(source, DOCUMENT_KEYS, "playlist document");
        if (!"tuneweave_uni_playlist_v1".equals(requiredString(source, "format"))) {
            throw new IllegalArgumentException("Unsupported Uni Playlist document format");
        }
        JsonArray sourceItems = requiredArray(source, "items");
        if (sourceItems.size() > MAX_ITEMS) throw new IllegalArgumentException("Playlist document is too large");
        JsonObject result = new JsonObject();
        result.addProperty("format", "tuneweave_uni_playlist_v1");
        result.addProperty("id", requiredString(source, "id"));
        result.addProperty("name", requiredName(requiredString(source, "name")));
        result.addProperty("description", normalizedDescription(optionalString(source, "description")));
        long created = requiredLong(source, "created_at_ms");
        long updated = requiredLong(source, "updated_at_ms");
        if (created < 0 || updated < created) throw new IllegalArgumentException("Playlist document timestamps are invalid");
        result.addProperty("created_at_ms", created);
        result.addProperty("updated_at_ms", updated);
        JsonArray items = new JsonArray();
        for (JsonObject raw : validStoredItems(sourceItems)) items.add(normalizeItem(raw, items.size(), true));
        result.addProperty("item_count", items.size());
        result.add("items", items);
        JsonObject extensions = new JsonObject();
        extensions.addProperty("duplicates_preserved", true);
        result.add("extensions", extensions);
        return result;
    }

    private static JsonObject normalizeItem(JsonObject source, int position, boolean preserveIdentity) {
        requireOnlyKeys(source, ITEM_KEYS, "playlist item");
        JsonObject result = new JsonObject();
        result.addProperty("id", preserveIdentity ? requiredString(source, "id") : newId("item_"));
        result.addProperty("position", position);
        String kind = requiredString(source, "kind");
        if (!Set.of("track", "video", "podcast_episode", "radio_station").contains(kind)) {
            throw new IllegalArgumentException("Unsupported local playlist item kind");
        }
        result.addProperty("kind", kind);
        String sourceRef = requiredString(source, "source_ref");
        if (!sourceRef.contains(":")) throw new IllegalArgumentException("Playlist item reference is invalid");
        result.addProperty("source_ref", sourceRef);
        result.add("snapshot", normalizeSnapshot(requiredObject(source, "snapshot")));
        result.addProperty("added_at_ms", preserveIdentity ? requiredLong(source, "added_at_ms") : System.currentTimeMillis());
        result.add("extensions", copyKnownObject(source, "extensions", ITEM_EXTENSION_KEYS));
        return result;
    }

    private static JsonObject normalizeSnapshot(JsonObject source) {
        requireOnlyKeys(source, SNAPSHOT_KEYS, "playlist item snapshot");
        JsonObject result = new JsonObject();
        result.addProperty("title", requiredString(source, "title"));
        result.add("artists", copyStringArray(source, "artists"));
        copyNullable(source, result, "album");
        copyNullable(source, result, "duration_ms");
        copyNullable(source, result, "isrc");
        copyNullable(source, result, "cover_url");
        result.add("version_tags", copyStringArray(source, "version_tags"));
        result.add("extensions", copyKnownObject(source, "extensions", SNAPSHOT_EXTENSION_KEYS));
        return result;
    }

    private static JsonObject copyKnownObject(JsonObject source, String key, Set<String> allowed) {
        JsonObject value = source.has(key) && source.get(key).isJsonObject()
                ? source.getAsJsonObject(key) : new JsonObject();
        requireOnlyKeys(value, allowed, key);
        return value.deepCopy();
    }

    private static JsonArray copyStringArray(JsonObject source, String key) {
        JsonArray input = "version_tags".equals(key) && (!source.has(key) || source.get(key).isJsonNull())
                ? new JsonArray() : requiredArray(source, key);
        JsonArray result = new JsonArray();
        ItemMapping.forEach("playlist snapshot text", input, value -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid snapshot text");
            result.add(value.getAsString());
        });
        return result;
    }

    private static void copyNullable(JsonObject source, JsonObject target, String key) {
        target.add(key, source.has(key) ? source.get(key).deepCopy() : com.google.gson.JsonNull.INSTANCE);
    }

    private static void requireOnlyKeys(JsonObject object, Set<String> allowed, String name) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown field in " + name + ": " + key);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) throw new IllegalArgumentException("Missing " + key);
        return object.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) throw new IllegalArgumentException("Missing " + key);
        return object.getAsJsonArray(key);
    }

    private static String requiredString(JsonObject object, String key) {
        String value = optionalString(object, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static String optionalString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static long requiredLong(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException("Missing " + key);
        return object.get(key).getAsLong();
    }

    private static String requiredName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isBlank() || value.length() > 200) throw new IllegalArgumentException("Playlist name is invalid");
        return value;
    }

    private static String normalizedDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.length() > 4_000) throw new IllegalArgumentException("Playlist description is too long");
        return value;
    }

    private static String id(String reference) {
        String value = reference == null ? "" : reference.trim();
        if (value.startsWith(LOCAL_PREFIX)) value = value.substring(LOCAL_PREFIX.length());
        if (value.isBlank()) throw new IllegalArgumentException("Local playlist reference is missing");
        return value;
    }

    static String reference(JsonObject document) {
        return LOCAL_PREFIX + document.get("id").getAsString();
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static void resetPositions(JsonArray items) {
        int position = 0;
        for (JsonObject item : validStoredItems(items)) item.addProperty("position", position++);
    }

    private static void touch(JsonObject document, int count) {
        document.addProperty("item_count", count);
        document.addProperty("updated_at_ms", System.currentTimeMillis());
    }

    private static JsonObject emptyDatabase() {
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", SCHEMA_VERSION);
        result.add("playlists", new JsonArray());
        return result;
    }

    private static Path path() {
        return indi.mopelotus.musichud.utils.LegacyDataMigration.uniPlaylists(MusicHud.getConfigDirectory());
    }
}
