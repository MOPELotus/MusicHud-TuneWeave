package indi.mopelotus.musichud.client.ui.pages.account;

import java.util.ArrayList;
import java.util.List;

/** Central feature gate for login methods exposed by the default client UI. */
public final class LoginFeaturePolicy {
    private static final boolean PASSWORD_LOGIN_ENABLED = false;
    private static final List<LoginMethod> ENABLED_METHODS = createEnabledMethods();

    private LoginFeaturePolicy() {
    }

    public static List<LoginMethod> enabledMethods() {
        return ENABLED_METHODS;
    }

    public static boolean isEnabled(LoginMethod method) {
        return ENABLED_METHODS.contains(method);
    }

    static void requireEnabled(LoginMethod method) {
        if (!isEnabled(method)) {
            throw new IllegalStateException(method + " login is disabled in this build");
        }
    }

    private static List<LoginMethod> createEnabledMethods() {
        List<LoginMethod> methods = new ArrayList<>();
        methods.add(LoginMethod.QR_CODE);
        if (PASSWORD_LOGIN_ENABLED) {
            methods.add(LoginMethod.PASSWORD);
        }
        methods.add(LoginMethod.DEVICE_CODE);
        return List.copyOf(methods);
    }

    public enum LoginMethod {
        QR_CODE,
        PASSWORD,
        DEVICE_CODE
    }
}
