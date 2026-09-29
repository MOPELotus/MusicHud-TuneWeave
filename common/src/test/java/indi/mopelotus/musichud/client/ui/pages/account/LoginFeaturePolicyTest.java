package indi.mopelotus.musichud.client.ui.pages.account;
import org.junit.jupiter.api.Test;
import java.util.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import static indi.mopelotus.musichud.client.ui.pages.account.LoginFeaturePolicy.LoginMethod.*;
import static org.junit.jupiter.api.Assertions.*;
class LoginFeaturePolicyTest {
    @Test void supportedTabsFollowPlatformAndCapabilities() {
        var all = Set.of("password_login", "phone_login", "qr_login", "credential_import");
        for (var p : TuneWeavePlatform.values()) {
            var methods = LoginFeaturePolicy.supportedMethods(p, all);
            assertFalse(methods.contains(CREDENTIAL_IMPORT));
            assertEquals(p == TuneWeavePlatform.KUWO || p == TuneWeavePlatform.MIGU, methods.contains(PASSWORD));
            assertTrue(LoginFeaturePolicy.supportedMethods(p, Set.of()).isEmpty());
        }
        assertEquals(List.of(PASSWORD,DEVICE_CODE), LoginFeaturePolicy.supportedMethods(TuneWeavePlatform.KUWO, Set.of("password_login","phone_login")));
        assertEquals(List.of(QR_CODE), LoginFeaturePolicy.supportedMethods(TuneWeavePlatform.BILIBILI, Set.of("qr_login")));
        assertEquals(List.of(DEVICE_CODE), LoginFeaturePolicy.supportedMethods(TuneWeavePlatform.MIGU, Set.of("phone_login")));
        assertThrows(IllegalStateException.class,()->LoginFeaturePolicy.requireEnabled(CREDENTIAL_IMPORT));
    }
}
