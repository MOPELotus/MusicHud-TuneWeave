package indi.mopelotus.musichud.server.api.tuneweave;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-local response secrets. Never include values in diagnostics or Minecraft payloads. */
public final class TuneWeaveCredentialUpdates {
    private TuneWeaveCredentialUpdates() {}

    public static Map<String, String> parse(JsonObject meta, List<String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (meta != null && meta.has("caller_credential")) {
            try {
                JsonObject credential = meta.getAsJsonObject("caller_credential");
                if (!"tuneweave_credential_v1".equals(string(credential, "format"))) throw invalid();
                put(result, string(credential, "platform"), string(credential, "value"));
            } catch (RuntimeException error) { throw invalid(); }
        }
        Map<String, String> fromHeaders = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null || header.length() > 1_048_576
                    || header.chars().anyMatch(c -> c < 32 && c != '\t' || c >= 127)) throw invalid();
            for (String item : header.split(",", -1)) {
                int separator = item.indexOf('=');
                if (separator <= 0) throw invalid();
                String platform = item.substring(0, separator).trim();
                String value = item.substring(separator + 1).trim();
                if (fromHeaders.containsKey(platform) && !fromHeaders.get(platform).equals(value)) throw invalid();
                put(fromHeaders, platform, value);
            }
        }
        result.putAll(fromHeaders); // The response header wins over an earlier JSON rotation.
        return Map.copyOf(result);
    }

    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }

    private static void put(Map<String, String> result, String platform, String value) {
        try { TuneWeavePlatform.requireApiName(platform); }
        catch (IllegalArgumentException error) { throw invalid(); }
        if (value == null || value.length() > 1_048_576 || !value.startsWith("twc1_") || value.length() <= 5
                || value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == ',')) throw invalid();
        result.put(platform, value);
    }

    private static TuneWeaveApiClient.TuneWeaveException invalid() {
        return new TuneWeaveApiClient.TuneWeaveException("Invalid TuneWeave credential update", false);
    }
}
