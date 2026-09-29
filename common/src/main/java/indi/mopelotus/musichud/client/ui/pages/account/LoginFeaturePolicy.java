package indi.mopelotus.musichud.client.ui.pages.account;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.List;
import java.util.Set;

/** Login tabs follow the selected provider's advertised capabilities. */
public final class LoginFeaturePolicy {
    private LoginFeaturePolicy() {}
    public static List<LoginMethod> enabledMethods() {
        return List.of(LoginMethod.QR_CODE, LoginMethod.PASSWORD, LoginMethod.DEVICE_CODE);
    }
    public static List<LoginMethod> supportedMethods(TuneWeavePlatform platform, Set<String> capabilities) {
        return enabledMethods().stream().filter(method -> switch (method) {
            case QR_CODE -> capabilities.contains("qr_login");
            case PASSWORD -> (platform == TuneWeavePlatform.KUWO || platform == TuneWeavePlatform.MIGU)
                    && capabilities.contains("password_login");
            case DEVICE_CODE -> capabilities.contains("phone_login");
            case CREDENTIAL_IMPORT -> false;
        }).toList();
    }
    public static boolean isEnabled(LoginMethod method) { return enabledMethods().contains(method); }
    static void requireEnabled(LoginMethod method) {
        if (!isEnabled(method)) throw new IllegalStateException(method + " login is disabled in this build");
    }
    public enum LoginMethod { QR_CODE, PASSWORD, DEVICE_CODE, CREDENTIAL_IMPORT }
}
