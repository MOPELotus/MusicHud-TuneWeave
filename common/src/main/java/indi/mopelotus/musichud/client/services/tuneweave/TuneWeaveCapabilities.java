package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;

final class TuneWeaveCapabilities {
    private TuneWeaveCapabilities() {}
    static java.util.Set<String> read(JsonElement data, String platform) {
        if (data != null && data.isJsonArray()) {
            for (var item : data.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                var value = item.getAsJsonObject();
                if (!matches(value.get("platform"), platform)) continue;
                var registered = value.get("registered");
                if (registered == null || !registered.isJsonPrimitive()
                        || !registered.getAsJsonPrimitive().isBoolean()) break;
                if (!registered.getAsBoolean()) return java.util.Set.of();
                var capabilities = value.get("capabilities");
                if (capabilities == null || !capabilities.isJsonArray()) break;
                var result = new java.util.HashSet<String>();
                for (var capability : capabilities.getAsJsonArray()) {
                    if (!capability.isJsonPrimitive() || !capability.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Malformed TuneWeave capabilities");
                    result.add(capability.getAsString());
                }
                return java.util.Set.copyOf(result);
            }
        }
        throw new IllegalArgumentException("Missing TuneWeave platform capabilities");
    }
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
