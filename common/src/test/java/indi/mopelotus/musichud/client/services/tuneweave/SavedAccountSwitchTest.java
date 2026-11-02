package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class SavedAccountSwitchTest {
    private static final TuneWeavePlatform PLATFORM = TuneWeavePlatform.NETEASE;

    private static final class Fixture {
        final Map<String, String> saved = new HashMap<>(Map.of("a", "twc1_a", "b", "twc1_b"));
        String selected = "a";
        Runnable duringResponse = () -> {};
        int requests;
        int profiles;
        final Map<TuneWeavePlatform, TuneWeaveSession> sessions = new HashMap<>();
        final TuneWeaveGateway gateway;
        final TuneWeaveAuthenticationService auth;
        Fixture() {
            var config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
                    new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> "netease";
                        case "getTuneWeaveCredential" -> "netease".equals(args[0]) ? saved.getOrDefault(selected, "") : "";
                        case "selectSavedAccount" -> {
                            String id = (String) args[1];
                            if (!saved.containsKey(id)) throw new IllegalArgumentException("Unknown account");
                            selected = id;
                            yield null;
                        }
                        case "setTuneWeaveCredential" -> { saved.put(selected, (String) args[1]); yield null; }
                        case "clearTuneWeaveCredential" -> { saved.remove(selected); selected = ""; yield null; }
                        case "storeAccountProfile" -> { profiles++; yield null; }
                        case "save" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
            gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
                requests++;
                var response = new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(
                        "{\"platform\":\"netease\",\"user_id\":\"" + selected
                                + "\",\"nickname\":\"Name\",\"avatar_url\":\"https://example.com/a\",\"authenticated\":true}"),
                        new JsonObject(), Map.of("netease", "twc1_rotated"));
                var action = duringResponse;
                duringResponse = () -> {};
                action.run();
                return response;
            });
            auth = new TuneWeaveAuthenticationService(gateway, sessions);
        }
    }

    @Test void rapidRoundTripRejectsQueuedRequestsAndOldLoginEvenWithSameCredential() {
        var f = new Fixture();
        f.sessions.put(PLATFORM, new TuneWeaveSession(PLATFORM, "a", "A", "", true));
        var queued = f.gateway.capture(() -> fail("Old work must not execute"));
        var login = f.auth.beginLogin(PLATFORM);
        f.auth.selectAccount(PLATFORM, "b");
        assertNull(f.auth.cachedSession(PLATFORM));
        f.auth.selectAccount(PLATFORM, "a");
        assertNull(f.auth.cachedSession(PLATFORM));
        assertThrows(CancellationException.class, queued::get);
        assertFalse(f.auth.isCurrent(login));
        assertThrows(CancellationException.class, () -> f.auth.publishLogin(login, () -> fail("Stale login published")));
        assertEquals("twc1_a", f.saved.get("a"));
        assertEquals("twc1_b", f.saved.get("b"));
        assertEquals(0, f.requests);
    }

    @Test void profileAndRotationFromBeforeRoundTripCannotPublish() {
        var f = new Fixture();
        f.duringResponse = () -> { f.auth.selectAccount(PLATFORM, "b"); f.auth.selectAccount(PLATFORM, "a"); };
        assertThrows(CancellationException.class, () -> f.auth.loadSession(PLATFORM));
        assertTrue(f.sessions.isEmpty());
        assertEquals(0, f.profiles);
        assertEquals("twc1_a", f.saved.get("a"));
        assertEquals("twc1_b", f.saved.get("b"));
    }

    @Test void oldLogoutCannotRemoveSwitchedBackAccountOrOtherSavedAccount() {
        var f = new Fixture();
        var logout = f.auth.prepareLogout(PLATFORM);
        f.auth.selectAccount(PLATFORM, "b");
        f.auth.selectAccount(PLATFORM, "a");
        logout.run();
        assertEquals(0, f.requests);
        assertEquals(2, f.saved.size());
    }

    @Test void currentLogoutDeletesOnlySelectedAccountWithoutSelectingAnother() {
        var f = new Fixture();
        f.auth.prepareLogout(PLATFORM).run();
        assertEquals("", f.selected);
        assertFalse(f.gateway.hasCredential(PLATFORM));
        assertEquals(Map.of("b", "twc1_b"), f.saved);
        f.auth.selectAccount(PLATFORM, "b");
        assertTrue(f.auth.loadSession(PLATFORM).authenticated());
        assertEquals("b", f.auth.cachedSession(PLATFORM).userId());
        assertEquals(1, f.profiles);
    }

    @Test void failedSelectionPreservesCurrentCredentialAndProfile() {
        var f = new Fixture();
        var original = new TuneWeaveSession(PLATFORM, "a", "A", "", true);
        f.sessions.put(PLATFORM, original);
        assertThrows(IllegalArgumentException.class, () -> f.auth.selectAccount(PLATFORM, "missing"));
        assertSame(original, f.auth.cachedSession(PLATFORM));
        assertEquals("a", f.selected);
    }
}
