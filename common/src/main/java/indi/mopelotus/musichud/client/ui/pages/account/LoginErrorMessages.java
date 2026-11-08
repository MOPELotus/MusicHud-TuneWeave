package indi.mopelotus.musichud.client.ui.pages.account;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

/** Selects safe localized login messages from structured service errors. */
final class LoginErrorMessages {
    private LoginErrorMessages() {}

    static String key(Throwable error) {
        for (int depth = 0; depth < 8 && error != null; depth++, error = error.getCause()) {
            if (!(error instanceof TuneWeaveException failure)) continue;
            var details = failure.getDetails();
            if (failure.getPlatform() == TuneWeavePlatform.QQ && "conflict".equals(failure.getCode())
                    && details.isJsonObject()) {
                var code = details.getAsJsonObject().get("platform_code");
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()
                        && "20279".equals(code.getAsString())) {
                    return MusicHud.MOD_ID + ".text.login.deviceLimitExceeded";
                }
            }
        }
        return MusicHud.MOD_ID + ".text.login.failed";
    }
}
