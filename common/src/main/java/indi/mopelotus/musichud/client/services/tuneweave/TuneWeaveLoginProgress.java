package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;

/** Public challenge presentation only; credentials are consumed separately by authentication. */
public record TuneWeaveLoginProgress(String state, JsonObject verification, List<Account> accounts,
                                    TuneWeaveSession profile) {
    public record Account(String userId, String nickname) {}
    public TuneWeaveLoginProgress {
        verification = verification == null ? new JsonObject() : verification.deepCopy();
        accounts = List.copyOf(accounts);
    }
    @Override public JsonObject verification() { return verification.deepCopy(); }
    @Override public String toString() { return "TuneWeaveLoginProgress[state=" + state + "]"; }
    static TuneWeaveLoginProgress read(JsonObject data, TuneWeaveSession profile) {
        String state = TuneWeaveJson.requiredString(data, "state");
        if (!Set.of("waiting", "scanned", "verification_required", "account_selection_required",
                "browser_verification_required", "confirmed", "expired", "failed").contains(state))
            throw new IllegalArgumentException("Unknown login state");
        var candidates = new java.util.ArrayList<Account>();
        if (data.has("accounts")) {
            if (!data.get("accounts").isJsonArray() || data.getAsJsonArray("accounts").size() > 100)
                throw new IllegalArgumentException("Invalid login candidates");
            var ids = new java.util.HashSet<String>();
            for (var item : data.getAsJsonArray("accounts")) {
                JsonObject value = TuneWeaveJson.object(item);
                String id = TuneWeaveJson.requiredString(value, "user_id");
                String nickname = TuneWeaveJson.string(value, "nickname");
                if (id.isBlank() || id.length() > 512 || nickname.length() > 512 || !ids.add(id))
                    throw new IllegalArgumentException("Invalid login candidate");
                candidates.add(new Account(id, nickname));
            }
        }
        if (state.equals("account_selection_required") && candidates.isEmpty())
            throw new IllegalArgumentException("Missing login candidates");
        JsonObject verification = new JsonObject();
        if (data.has("verification") && !data.get("verification").isJsonNull()) {
            if (!data.get("verification").isJsonObject()) throw new IllegalArgumentException("Invalid login verification");
            verification = data.getAsJsonObject("verification");
            if (verification.toString().length() > 1_048_576) throw new IllegalArgumentException("Oversized login verification");
        }
        return new TuneWeaveLoginProgress(state, verification, candidates, profile);
    }
}
