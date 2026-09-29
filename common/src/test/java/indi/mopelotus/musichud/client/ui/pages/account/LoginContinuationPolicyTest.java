package indi.mopelotus.musichud.client.ui.pages.account;
import com.google.gson.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoginContinuationPolicyTest {
    @Test void wrongAnswersCanBeCorrectedButConsumedUnknownAndReplacedTransactionsCannotResume() {
        assertTrue(LoginContinuationPolicy.canContinue(new TuneWeaveException("wrong code", 401, "authentication_required", false, JsonNull.INSTANCE)));
        for (String flag : new String[]{"true", "\"false\"", "null", "[]"})
            assertFalse(LoginContinuationPolicy.canContinue(new TuneWeaveException("consumed", 401, "authentication_required", false,
                    JsonParser.parseString("{\"challenge_consumed\":" + flag + "}"))));
        assertFalse(LoginContinuationPolicy.canContinue(new TuneWeaveException("transport", true)));
        assertFalse(LoginContinuationPolicy.canContinue(new TuneWeaveException("replaced", 409, "conflict", true, JsonNull.INSTANCE)));
    }
}
