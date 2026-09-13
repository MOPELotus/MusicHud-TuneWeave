package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, strict helpers for the deliberately loose TuneWeave JSON envelopes. */
final class TuneWeaveJson {
    private TuneWeaveJson() {
    }

    static JsonObject object(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "TuneWeave response is missing an object", false);
        }
        return element.getAsJsonObject();
    }

    static String requiredString(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "TuneWeave response is missing " + key, false);
        }
        return value;
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value instanceof JsonNull || value.isJsonNull()
                ? null : value.getAsString();
    }

    static String string(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value == null ? fallback : value;
    }

    static String referenceValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonObject()) return string(element.getAsJsonObject(), "ref", "");
        return element.getAsString();
    }

    static String imageUrl(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key, "");
            if (!value.isBlank()) return normalizeImageUrl(value);
        }
        return "";
    }

    static String normalizeImageUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("http://")
                ? "https://" + trimmed.substring("http://".length()) : trimmed;
    }

    static List<JsonElement> elements(JsonElement value) {
        if (value == null || value.isJsonNull()) return List.of();
        if (value.isJsonArray()) {
            List<JsonElement> result = new ArrayList<>();
            value.getAsJsonArray().forEach(result::add);
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : List.of("items", "playlists", "results")) {
                JsonElement nested = object.get(key);
                if (nested instanceof JsonArray) {
                    List<JsonElement> result = new ArrayList<>();
                    nested.getAsJsonArray().forEach(result::add);
                    return result;
                }
            }
        }
        return List.of();
    }

    static JsonObject unwrap(JsonElement element) {
        if (element == null || !element.isJsonObject()) return new JsonObject();
        JsonObject object = element.getAsJsonObject();
        return object.has("data") && object.get("data").isJsonObject()
                ? object.getAsJsonObject("data") : object;
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    static Map<String, String> stringMap(JsonElement element) {
        if (element == null || !element.isJsonObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        element.getAsJsonObject().entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonNull()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        });
        return result;
    }

    static List<String> stringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        return elements(element).stream()
                .filter(value -> value != null && !value.isJsonNull() && value.isJsonPrimitive())
                .map(JsonElement::getAsString)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    static JsonObject mergeSnapshot(JsonObject object) {
        JsonElement snapshotData = object.get("snapshot");
        if (snapshotData == null || !snapshotData.isJsonObject()) return object;
        JsonObject merged = object.deepCopy();
        merged.remove("snapshot");
        snapshotData.getAsJsonObject().entrySet().forEach(entry -> {
            if (!merged.has(entry.getKey()) || merged.get(entry.getKey()).isJsonNull()) {
                merged.add(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return merged;
    }
}
