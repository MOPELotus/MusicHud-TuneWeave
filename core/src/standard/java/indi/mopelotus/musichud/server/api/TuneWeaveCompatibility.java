package indi.mopelotus.musichud.server.api;

import com.google.gson.*;
import java.util.HashSet;
import java.util.Set;

/** A publisher's compatibility declaration, distinct from the /v1 route prefix. */
public final class TuneWeaveCompatibility {
    public enum Evaluation { COMPATIBLE, UNKNOWN, INCOMPATIBLE }
    public static void requireCompatible(ApiServerFetcher.TuneWeaveManifest manifest) {
        if (manifest == null || evaluate(indi.mopelotus.musichud.utils.JsonUtil.gson.toJson(manifest), manifest.getVersion()) != Evaluation.COMPATIBLE)
            throw new UnconfirmedCompatibility();
    }
    public static final class UnconfirmedCompatibility extends IllegalStateException {
        public UnconfirmedCompatibility() { super("TuneWeave compatibility has not been declared for this client"); }
    }
    public static boolean causedByUnconfirmedCompatibility(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof UnconfirmedCompatibility) return true;
        return false;
    }
    public static final String PROFILE = "tuneweave-http-v1";
    public static final String LEGACY_VERSION = "1.0.0-beta.1";
    public static final String LEGACY_PROFILE = "tuneweave-http-v1";
    private TuneWeaveCompatibility() {}
    public static Evaluation evaluate(ApiBinaryUpdateService.UpdateOffer offer) {
        return evaluate(offer.manifestJson(), offer.targetVersion());
    }
    public static Evaluation evaluate(String manifestJson, String version) {
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
        if (!number(manifest, "schema_version", "1") || !text(manifest, "project", "TuneWeave"))
            throw new IllegalArgumentException("Unknown service manifest");
        if (!text(manifest, "api_version", "v1")) return Evaluation.INCOMPATIBLE;
        JsonElement declared = manifest.get("compatibility_profiles");
        if (declared == null) {
            // Only the already verified release can bridge historical manifests without a declaration.
            return LEGACY_VERSION.equals(version) && PROFILE.equals(LEGACY_PROFILE) ? Evaluation.COMPATIBLE : Evaluation.UNKNOWN;
        }
        if (!declared.isJsonArray() || declared.getAsJsonArray().isEmpty() || declared.getAsJsonArray().size() > 16)
            throw new IllegalArgumentException("Malformed service compatibility profiles");
        Set<String> profiles = new HashSet<>();
        for (JsonElement element : declared.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Malformed service compatibility profile");
            String profile = element.getAsString();
            if (!profile.matches("[a-z0-9][a-z0-9._-]{0,63}") || !profiles.add(profile))
                throw new IllegalArgumentException("Invalid service compatibility profile");
        }
        return profiles.contains(PROFILE) ? Evaluation.COMPATIBLE : Evaluation.UNKNOWN;
    }
    private static boolean number(JsonObject object, String field, String expected) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsString().equals(expected);
    }
    private static boolean text(JsonObject object, String field, String expected) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().equals(expected);
    }
}
