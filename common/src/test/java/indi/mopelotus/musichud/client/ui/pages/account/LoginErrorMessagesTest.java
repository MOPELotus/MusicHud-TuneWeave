package indi.mopelotus.musichud.client.ui.pages.account;

import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class LoginErrorMessagesTest {
    private static TuneWeaveException error(TuneWeavePlatform platform, String code, String details) {
        return new TuneWeaveException("untrusted server message", 409, code, false,
                JsonParser.parseString(details), Map.of(), platform);
    }

    @Test void qqDeviceLimitUsesAnActionableMessageIncludingWrappedFailures() {
        var failure = error(TuneWeavePlatform.QQ, "conflict", "{\"platform_code\":20279}");
        String expected = MusicHud.MOD_ID + ".text.login.deviceLimitExceeded";
        assertEquals(expected, LoginErrorMessages.key(failure));
        assertEquals(expected, LoginErrorMessages.key(new CompletionException(failure)));
    }

    @Test void unrelatedOrMalformedErrorsNeverBecomeDeviceLimitWarnings() {
        String fallback = MusicHud.MOD_ID + ".text.login.failed";
        for (String details : new String[]{"null", "[]", "20279", "{}", "{\"platform_code\":null}",
                "{\"platform_code\":true}", "{\"platform_code\":[]}", "{\"platform_code\":{}}",
                "{\"platform_code\":\"20279\"}", "{\"platform_code\":20272}"}) {
            assertEquals(fallback, LoginErrorMessages.key(error(TuneWeavePlatform.QQ, "conflict", details)), details);
        }
        assertEquals(fallback, LoginErrorMessages.key(error(TuneWeavePlatform.NETEASE, "conflict", "{\"platform_code\":20279}")));
        assertEquals(fallback, LoginErrorMessages.key(error(null, "conflict", "{\"platform_code\":20279}")));
        assertEquals(fallback, LoginErrorMessages.key(error(TuneWeavePlatform.QQ, "upstream_error", "{\"platform_code\":20279}")));
        assertEquals(fallback, LoginErrorMessages.key(new IllegalStateException("20279")));
        assertEquals(fallback, LoginErrorMessages.key(null));
    }
}
