package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;

/** Submission acknowledgement is independent of publication and ordinary playlist ownership. */
public record TuneWeaveSubmissionOutcome(boolean acknowledged, boolean metadataUpdated, long removedRecords,
                                         Boolean ownedPlaylistPresent, Boolean published) {
    static TuneWeaveSubmissionOutcome read(JsonObject data, String reference, boolean deletion) {
        if (!reference.equals(TuneWeaveJson.requiredString(data, "playlist_ref")))
            throw new IllegalArgumentException("Submission result reference mismatch");
        long removed = 0;
        if (deletion) {
            var value = data.get("removed_records");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Invalid submission record count");
            try { removed = value.getAsBigDecimal().longValueExact(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid submission record count", error); }
            if (removed < 0) throw new IllegalArgumentException("Negative submission record count");
        }
        return new TuneWeaveSubmissionOutcome(requiredBoolean(data, deletion ? "confirmed" : "accepted"),
                !deletion && requiredBoolean(data, "metadata_updated"), removed,
                deletion ? requiredBoolean(data, "owned_playlist_present") : null,
                data.get("published") == null || data.get("published").isJsonNull() ? null : requiredBoolean(data, "published"));
    }
    private static boolean requiredBoolean(JsonObject data, String key) {
        var value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid submission result flag");
        return value.getAsBoolean();
    }
}
