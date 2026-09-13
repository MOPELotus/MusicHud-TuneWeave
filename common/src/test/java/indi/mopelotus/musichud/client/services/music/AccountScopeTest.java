package indi.mopelotus.musichud.client.services.music;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class AccountScopeTest {
    @Test void derivesStableScopeFromAuthenticatedSession() {
        var session = new TuneWeaveSession(TuneWeavePlatform.NETEASE, "user-1", "", "", true);
        assertEquals(new AccountScope("netease", "user-1"), AccountScope.fromSession(session));
        assertEquals(AccountScope.ANONYMOUS, AccountScope.fromSession(null));
    }
}
