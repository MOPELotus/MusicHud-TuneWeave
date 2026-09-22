package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Optional, client-only normalized membership display data; unknown status stays unknown. */
public record TuneWeaveMembership(Long level, Boolean active, String iconUrl) {
    public static final TuneWeaveMembership NONE = new TuneWeaveMembership(null, null, "");
    static TuneWeaveMembership parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) return NONE;
        JsonObject data = value.getAsJsonObject();
        Long level = null;
        JsonElement raw = data.get("level");
        if (raw != null && raw.isJsonPrimitive()) try {
            long number = raw.getAsBigDecimal().longValueExact();
            if (number >= 0 && number <= 0xffffffffL) level = number;
        } catch (RuntimeException ignored) { }
        raw = data.get("active");
        Boolean active = raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()
                ? raw.getAsBoolean() : null;
        raw = data.get("icon_url");
        String icon = raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
                ? raw.getAsString() : "";
        return new TuneWeaveMembership(level, active, icon);
    }
}
