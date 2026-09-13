package indi.mopelotus.musichud.client.ui.pages.account;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginFeaturePolicyTest {
    @Test
    void passwordLoginIsNotExposedByDefault() {
        assertEquals(List.of(
                        LoginFeaturePolicy.LoginMethod.QR_CODE,
                        LoginFeaturePolicy.LoginMethod.DEVICE_CODE),
                LoginFeaturePolicy.enabledMethods());
        assertFalse(LoginFeaturePolicy.isEnabled(LoginFeaturePolicy.LoginMethod.PASSWORD));
    }

    @Test
    void directPasswordRouteIsRejectedWhileFeatureIsDisabled() {
        assertThrows(IllegalStateException.class, () ->
                LoginFeaturePolicy.requireEnabled(LoginFeaturePolicy.LoginMethod.PASSWORD));
    }
}
