package indi.mopelotus.musichud.client.ui.pages.account;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
/** A user may correct a rejected answer; no login request is automatically repeated here. */
final class LoginContinuationPolicy {
    private LoginContinuationPolicy() {}
    static boolean canContinue(TuneWeaveException error) {
        if (error.getStatusCode() == 0 || "conflict".equals(error.getCode()) || "not_found".equals(error.getCode())) return false;
        var details = error.getDetails();
        if (details != null && details.isJsonObject() && details.getAsJsonObject().has("challenge_consumed")) {
            var flag = details.getAsJsonObject().get("challenge_consumed");
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean() || flag.getAsBoolean()) return false;
        }
        return error.isRetryable() || java.util.Set.of("invalid_request", "authentication_required", "permission_denied").contains(error.getCode());
    }
}
