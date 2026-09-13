package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveResponse;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveRecentHistory.*;

/** Reads a bounded provider window; never merges, deduplicates or caches private history. */
final class TuneWeaveRecentHistoryService {
    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final TuneWeaveAccountRequests accounts;

    TuneWeaveRecentHistoryService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                                 TuneWeaveAccountRequests accounts) {
        this.gateway = gateway; this.entities = entities; this.accounts = accounts;
    }

    TuneWeaveRecentHistory load(TuneWeavePlatform platform, Kind kind) {
        Objects.requireNonNull(platform); Objects.requireNonNull(kind);
        // Capture before discovery too: a slow capabilities response cannot adopt a new account/epoch.
        var accountRequest = accounts.prepare(platform, () -> parse(platform, kind,
                gateway.requestForPlatform(platform, "GET", "/v1/account/history/" + kind.path(),
                        Map.of("platform", platform.apiName(), "limit", Integer.toString(LIMIT), "offset", "0"), null)));
        return gateway.capture(() -> {
            if (!gateway.hasCredential(platform)) {
                throw new TuneWeaveException("Recent history requires login", 401,
                        "authentication_required", false, JsonNull.INSTANCE);
            }
            var capabilities = gateway.requestWithoutCredential("GET", "/v1/capabilities",
                    Map.of("platform", platform.apiName()), null).data();
            if (!TuneWeaveCapabilities.supports(capabilities, platform.apiName(), kind.capability())) {
                throw new TuneWeaveException("Recent history is not supported", 422,
                        "capability_not_supported", false, JsonNull.INSTANCE);
            }
            return accountRequest.get();
        }).get();
    }

    private record Raw(JsonObject resource, Instant playedAt, Device device) {}

    TuneWeaveRecentHistory parse(TuneWeavePlatform platform, Kind kind, TuneWeaveResponse response) {
        JsonElement data = response.data();
        require(data != null && data.isJsonArray() && data.getAsJsonArray().size() <= LIMIT);
        JsonObject page = object(object(response.meta()).get("pagination"));
        require(number(page.get("limit"), false) == LIMIT && number(page.get("offset"), false) == 0);
        require(page.has("next_offset") && page.get("next_offset").isJsonNull());
        require(isFalse(page.get("has_more")));
        require(isFalse(object(page.get("extensions")).get("continuation_supported")));
        Long total = number(page.get("total"), true);

        // Validate every record before the shared entity mapper publishes any summaries.
        var raw = new ArrayList<Raw>();
        for (JsonElement element : data.getAsJsonArray()) {
            JsonObject entry = object(element);
            JsonObject resource = canonicalResource(kind, object(entry.get(kind.field())));
            String reference = text(resource.get("ref"), true);
            require(reference.length() <= 512 && reference.startsWith(platform.apiName() + ":"));
            require(TuneWeaveReference.id(reference).equals(text(resource.get("id"), true)));
            require(platform.apiName().equals(text(resource.get("platform"), true)));
            text(resource.get("name"), true);
            validateSummary(platform, resource);
            Instant time = timestamp(entry.get("played_at"));
            Device device = null;
            if (entry.has("device") && !entry.get("device").isJsonNull()) {
                JsonObject value = object(entry.get("device"));
                device = new Device(text(value.get("name"), false), text(value.get("operating_system"), false));
            }
            raw.add(new Raw(resource, time, device));
        }
        var entries = new ArrayList<Entry<?>>();
        for (Raw entry : raw) {
            Object resource = switch (kind) {
                case TRACKS -> entities.toTrack(platform, entry.resource());
                case ALBUMS -> entities.toAlbum(platform, entry.resource());
                case PLAYLISTS -> entities.toPlaylist(platform, entry.resource(), false);
            };
            entries.add(new Entry<>(resource, entry.playedAt(), entry.device()));
        }
        return new TuneWeaveRecentHistory(kind, entries, total);
    }

    private static JsonObject canonicalResource(Kind kind, JsonObject input) {
        // General catalog mapping also supports snapshots and videos. History has typed canonical
        // resources: unknown fields must not change their kind or bypass validation via a snapshot.
        JsonObject resource = project(input, "ref", "platform", "id", "name");
        String[] fields = switch (kind) {
            case TRACKS -> new String[]{"artists", "album", "duration_ms"};
            case ALBUMS -> new String[]{"artists", "cover_url", "kind", "company", "track_count"};
            case PLAYLISTS -> new String[]{"creator", "cover_url", "track_count", "extensions", "visibility"};
        };
        project(input, fields).entrySet().forEach(field -> resource.add(field.getKey(), field.getValue()));
        for (String field : new String[]{"album", "creator"}) {
            JsonElement value = resource.get(field);
            if (value != null && !value.isJsonNull()) {
                resource.add(field, "album".equals(field) ? project(object(value), "ref", "name", "cover_url")
                        : project(object(value), "ref", "name"));
            }
        }
        JsonElement artists = resource.get("artists");
        if (artists != null && !artists.isJsonNull()) {
            require(artists.isJsonArray());
            JsonArray summaries = new JsonArray();
            for (JsonElement value : artists.getAsJsonArray()) summaries.add(project(object(value), "ref", "name"));
            resource.add("artists", summaries);
        }
        return resource;
    }

    private static JsonObject project(JsonObject input, String... fields) {
        JsonObject result = new JsonObject();
        for (String field : fields) if (input.has(field)) result.add(field, input.get(field).deepCopy());
        return result;
    }

    private static void validateSummary(TuneWeavePlatform platform, JsonObject resource) {
        for (String key : new String[]{"name", "cover_url", "kind", "company"}) text(resource.get(key), false);
        for (String key : new String[]{"duration_ms", "track_count", "play_count"}) {
            Long value = number(resource.get(key), true);
            require(value == null || value <= Integer.MAX_VALUE);
        }
        for (String key : new String[]{"album", "creator"}) {
            JsonElement value = resource.get(key);
            if (value != null && !value.isJsonNull()) validateReferenceSummary(platform, object(value));
        }
        JsonElement artists = resource.get("artists");
        if (artists != null && !artists.isJsonNull()) {
            require(artists.isJsonArray());
            for (JsonElement value : artists.getAsJsonArray()) validateReferenceSummary(platform, object(value));
        }
    }

    private static void validateReferenceSummary(TuneWeavePlatform platform, JsonObject value) {
        String ref = text(value.get("ref"), false);
        require(ref.isBlank() || ref.length() <= 512 && ref.startsWith(platform.apiName() + ":")
                && !TuneWeaveReference.id(ref).isBlank());
        text(value.get("name"), false); text(value.get("cover_url"), false);
    }

    private static Instant timestamp(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        String text = text(value, true);
        require(text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"));
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (DateTimeParseException error) { throw malformed(); }
    }

    private static Long number(JsonElement value, boolean optional) {
        if (optional && (value == null || value.isJsonNull())) return null;
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber());
        try {
            long result = value.getAsBigDecimal().longValueExact();
            require(result >= 0);
            return result;
        } catch (ArithmeticException | NumberFormatException error) { throw malformed(); }
    }

    private static String text(JsonElement value, boolean required) {
        if (!required && (value == null || value.isJsonNull())) return "";
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString());
        String text = value.getAsString();
        require(text.length() <= 16_384 && (!required || !text.isBlank()));
        return text;
    }

    private static JsonObject object(JsonElement value) {
        require(value != null && value.isJsonObject());
        return value.getAsJsonObject();
    }
    private static boolean isFalse(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean();
    }
    private static void require(boolean valid) { if (!valid) throw malformed(); }
    private static TuneWeaveException malformed() { return new TuneWeaveException("Invalid recent history response", false); }
}
