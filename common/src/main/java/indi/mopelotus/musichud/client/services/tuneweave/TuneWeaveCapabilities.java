package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;

final class TuneWeaveCapabilities {
    private TuneWeaveCapabilities() {}
    static boolean supports(JsonElement data, String platform, String capability) {
        if (data == null || !data.isJsonArray()) return false;
        for (var value : data.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            var status = value.getAsJsonObject();
            var registered = status.get("registered");
            if (!matches(status.get("platform"), platform) || registered == null || !registered.isJsonPrimitive()
                    || !registered.getAsJsonPrimitive().isBoolean() || !registered.getAsBoolean()) continue;
            var capabilities = status.get("capabilities");
            if (capabilities == null || !capabilities.isJsonArray()) continue;
            for (var item : capabilities.getAsJsonArray()) if (matches(item, capability)) return true;
        }
        return false;
    }
    private static boolean matches(JsonElement value, String expected) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && expected.equals(value.getAsString());
    }
}
