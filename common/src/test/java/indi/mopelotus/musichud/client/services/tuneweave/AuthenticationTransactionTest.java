package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationTransactionTest {
    private static final TuneWeavePlatform PLATFORM = TuneWeavePlatform.NETEASE;
    private static final class Fixture {
        final AtomicReference<String> credential = new AtomicReference<>("twc1_initial");
        final List<String> paths = new ArrayList<>();
        Runnable duringResponse = () -> {};
        String profilePlatform = "netease";
        String submittedCode;
        final TuneWeaveGateway gateway;
        final TuneWeaveAuthenticationService auth;
        Fixture() {
            ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> "netease";
                        case "getTuneWeaveCredential" -> credential.get();
                        case "setTuneWeaveCredential" -> { credential.set((String)args[1]); yield null; }
                        case "clearTuneWeaveCredential" -> { credential.set(""); yield null; }
                        case "setDefaultMusicPlatform", "save" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
            gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
                paths.add(path);
                Runnable callback = duringResponse; duringResponse = () -> {}; callback.run();
                if (path.equals("/v1/auth/qr") || path.equals("/v1/auth/challenges"))
                    return response("{\"transaction_id\":\"transaction\",\"url\":\"https://example.com/qr\"}");
                if (path.equals("/v1/auth/session"))
                    return response("{\"platform\":\"" + profilePlatform + "\",\"user_id\":\"user\",\"nickname\":\"Name\",\"avatar_url\":\"https://example.com/a\",\"authenticated\":true}");
                if (path.endsWith("/verify")) submittedCode = body.getAsJsonObject().get("code").getAsString();
                return response("{\"state\":\"confirmed\",\"caller_credential\":{\"format\":\"tuneweave_credential_v1\",\"platform\":\"netease\",\"value\":\"twc1_confirmed\"}}");
            });
            auth = new TuneWeaveAuthenticationService(gateway, new HashMap<>());
        }
        private TuneWeaveApiClient.TuneWeaveResponse response(String json) {
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
        }
    }

    @Test void lateQrConfirmationCannotOverwriteReplacementCredential() {
        var f = new Fixture(); var session = f.auth.startQrLogin(PLATFORM, null);
        f.duringResponse = () -> f.credential.set("twc1_replacement");
        assertThrows(CancellationException.class, () -> f.auth.pollQrLogin(session));
        assertEquals("twc1_replacement", f.credential.get()); assertNull(f.auth.cachedSession(PLATFORM));
    }

    @Test void cancelledCreationCannotRegisterPollingAndOldDetachDoesNotCancelNewAttempt() {
        var f = new Fixture(); var old = f.auth.beginLogin(PLATFORM);
        f.duringResponse = () -> f.auth.cancelLogin(old);
        assertThrows(CancellationException.class, () -> f.auth.startQrLogin(old, null));
        var next = f.auth.beginLogin(PLATFORM);
        f.auth.cancelLogin(old); assertTrue(f.auth.isCurrent(next));
        var qr = f.auth.startQrLogin(next, null);
        f.auth.cancelLogin(next);
        int requests = f.paths.size();
        assertThrows(CancellationException.class, () -> f.auth.pollQrLogin(qr));
        assertEquals(requests, f.paths.size());
    }

    @Test void logoutAndNewLoginInvalidateAnInFlightConfirmationEvenWithTheSameCredential() {
        for (boolean logout : new boolean[]{false, true}) {
            var f = new Fixture(); var session = f.auth.startQrLogin(PLATFORM, null);
            f.duringResponse = () -> { if (logout) f.auth.prepareLogout(PLATFORM); else f.auth.beginLogin(PLATFORM); };
            assertThrows(CancellationException.class, () -> f.auth.pollQrLogin(session));
            assertEquals("twc1_initial", f.credential.get());
        }
    }

    @Test void confirmedTransactionIsConsumedAndSmsCodeKeepsLeadingZero() {
        var f = new Fixture(); var qr = f.auth.startQrLogin(PLATFORM, null);
        assertTrue(f.auth.pollQrLogin(qr).profile().authenticated());
        assertThrows(CancellationException.class, () -> f.auth.pollQrLogin(qr));
        var sms = f.auth.startSmsLogin(PLATFORM, "0123456789", "44");
        assertTrue(f.auth.verifySmsLogin(sms, "012345").authenticated());
        assertEquals("012345", f.submittedCode);
        assertThrows(CancellationException.class, () -> f.auth.verifySmsLogin(sms, "012345"));
    }

    @Test void staleRefreshCannotOverwriteAndUnknownPlatformIsRejected() {
        var f = new Fixture(); f.duringResponse = () -> f.credential.set("twc1_replacement");
        assertThrows(CancellationException.class, () -> f.auth.refreshSession(PLATFORM));
        assertEquals("twc1_replacement", f.credential.get());
        for (String platform : List.of("unknown-provider", "", "netease ", "NETEASE")) {
            var invalid = new Fixture(); invalid.profilePlatform = platform;
            assertThrows(RuntimeException.class, () -> invalid.auth.loadSession(PLATFORM));
            assertNull(invalid.auth.cachedSession(PLATFORM));
            var credential = JsonParser.parseString("{\"format\":\"tuneweave_credential_v1\",\"platform\":\"" + platform + "\",\"value\":\"twc1_bad\"}");
            assertThrows(RuntimeException.class, () -> invalid.gateway.saveCredential(PLATFORM, credential));
            assertEquals("twc1_initial", invalid.credential.get());
        }
    }

    @Test void logoutAndProfileCallbacksCannotCrossAnAuthenticationEpochWithUnchangedCredential() {
        var f = new Fixture();
        Runnable logout = f.auth.prepareLogout(PLATFORM);
        var newer = f.auth.beginLogin(PLATFORM);
        f.auth.cancelLogin(newer); // Even cancelling a newer attempt must not revive an older logout.
        int requests = f.paths.size(); logout.run();
        assertEquals(requests, f.paths.size()); assertEquals("twc1_initial", f.credential.get());
        f.duringResponse = () -> f.auth.beginLogin(PLATFORM);
        assertThrows(CancellationException.class, () -> f.auth.loadSession(PLATFORM));
        assertNull(f.auth.cachedSession(PLATFORM));
    }
}
