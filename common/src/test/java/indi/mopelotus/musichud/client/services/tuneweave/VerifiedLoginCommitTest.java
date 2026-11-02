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

class VerifiedLoginCommitTest {
    private static final TuneWeavePlatform PLATFORM = TuneWeavePlatform.NETEASE;
    private static final class Fixture {
        final Map<String, String> accounts = new HashMap<>();
        String selected = "user-0";
        String profileUser = "user-0";
        int newLoginWrites;
        int rotationWrites;
        boolean failProfile;
        boolean enrichProfile;
        boolean rotateCandidate;
        Runnable duringProfile = () -> {};
        final TuneWeaveGateway gateway;
        final TuneWeaveAuthenticationService auth;
        Fixture() {
            for (int i = 0; i < 32; i++) accounts.put("user-" + i, "twc1_" + i);
            var config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
                    new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> "netease";
                        case "getSavedAccounts" -> java.util.List.of(new indi.mopelotus.musichud.beans.user.SavedAccount(
                                "slot-0", "user-0", "Name", "", true));
                        case "getTuneWeaveCredential" -> "netease".equals(args[0]) ? accounts.get(selected) : "";
                        case "storeLoginAccount" -> {
                            String user = (String) args[2];
                            if (!accounts.containsKey(user) && accounts.size() == 32) throw new IllegalStateException("Account limit");
                            newLoginWrites++;
                            accounts.put(user, (String) args[1]);
                            selected = user;
                            yield null;
                        }
                        case "setTuneWeaveCredential" -> { rotationWrites++; accounts.put(selected, (String) args[1]); yield null; }
                        case "storeAccountProfile", "setDefaultMusicPlatform", "save" -> null;
                        default -> throw new AssertionError("Unexpected persistence operation " + method.getName());
                    });
            gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
                if (path.equals("/v1/auth/qr")) return response("{\"transaction_id\":\"qr\",\"url\":\"https://example.test/qr\"}");
                if (path.equals("/v1/auth/session/refresh") || path.equals("/v1/auth/qr/qr")) return response(
                        "{\"state\":\"confirmed\",\"caller_credential\":{\"format\":\"tuneweave_credential_v1\",\"platform\":\"netease\",\"value\":\"twc1_candidate\"}}");
                if (path.equals("/v1/auth/session")) {
                    assertEquals(java.util.List.of("twc1_candidate"), credentials);
                    assertEquals("twc1_0", accounts.get("user-0"), "Candidate must remain uncommitted during profile request");
                    duringProfile.run();
                    if (failProfile) throw new TuneWeaveApiClient.TuneWeaveException("offline", false);
                    String profile = "{\"platform\":\"netease\",\"user_id\":\"" + profileUser
                            + "\",\"nickname\":\"Name\",\"avatar_url\":\"" + (enrichProfile ? "" : "https://example.test/a")
                            + "\",\"authenticated\":true}";
                    return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(profile), new JsonObject(),
                            rotateCandidate ? Map.of("netease", "twc1_candidate_rotated") : Map.of());
                }
                assertEquals("/v1/account/profile", path);
                assertEquals(java.util.List.of("twc1_candidate_rotated"), credentials);
                return response("{\"user\":{\"id\":\"user-0\",\"name\":\"Name\",\"avatar_url\":\"https://example.test/a\"}}");
            });
            auth = new TuneWeaveAuthenticationService(gateway, new HashMap<>());
        }
        TuneWeaveSession login() {
            return auth.pollQrLogin(auth.startQrLogin(PLATFORM, null)).profile();
        }
        private static TuneWeaveApiClient.TuneWeaveResponse response(String json) {
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
        }
    }

    @Test void refreshAtCapacityRotatesCurrentSlotAndPreservesIdentityAndQueuedRequests() {
        var f = new Fixture();
        Object identity = f.gateway.accountIdentity(PLATFORM);
        var queued = f.gateway.capture(() -> f.gateway.credential(PLATFORM));
        assertEquals("user-0", f.auth.refreshSession(PLATFORM).userId());
        assertEquals(32, f.accounts.size());
        assertEquals(0, f.newLoginWrites);
        assertEquals(1, f.rotationWrites);
        assertSame(identity, f.gateway.accountIdentity(PLATFORM));
        assertEquals("twc1_candidate", queued.get());
    }

    @Test void sameUserLoginAtCapacityIsOneAtomicWriteAndCreatesANewRequestIdentity() {
        var f = new Fixture();
        Object identity = f.gateway.accountIdentity(PLATFORM);
        var old = f.gateway.capture(() -> fail("Old login jobs must expire"));
        assertEquals("user-0", f.login().userId());
        assertEquals(32, f.accounts.size());
        assertEquals(1, f.newLoginWrites);
        assertEquals(0, f.rotationWrites);
        assertNotSame(identity, f.gateway.accountIdentity(PLATFORM));
        assertThrows(CancellationException.class, old::get);
    }

    @Test void failedCandidateProfileLeavesSelectedAccountAndAllSavedSlotsIntact() {
        for (boolean refresh : new boolean[]{false, true}) {
            var f = new Fixture(); f.failProfile = true;
            var before = Map.copyOf(f.accounts);
            Object identity = f.gateway.accountIdentity(PLATFORM);
            assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> {
                if (refresh) f.auth.refreshSession(PLATFORM); else f.login();
            });
            assertEquals(before, f.accounts);
            assertEquals("user-0", f.selected);
            assertEquals(0, f.newLoginWrites + f.rotationWrites);
            assertSame(identity, f.gateway.accountIdentity(PLATFORM));
            assertNull(f.auth.cachedSession(PLATFORM));
        }
    }

    @Test void aDifferentAccountAtCapacityFailsWithoutReplacingAnySavedAccount() {
        var f = new Fixture(); f.profileUser = "new-user";
        var before = Map.copyOf(f.accounts);
        assertThrows(IllegalStateException.class, f::login);
        assertEquals(before, f.accounts);
        assertEquals("user-0", f.selected);
        assertNull(f.auth.cachedSession(PLATFORM));
    }

    @Test void cancellationDuringCandidateVerificationCannotCommitEvenThoughOldCredentialIsUnchanged() {
        var f = new Fixture();
        f.duringProfile = () -> f.auth.cancelLogins();
        assertThrows(CancellationException.class, f::login);
        assertEquals("twc1_0", f.accounts.get("user-0"));
        assertEquals(0, f.newLoginWrites + f.rotationWrites);
        assertNull(f.auth.cachedSession(PLATFORM));
    }

    @Test void refreshCannotKeepIdentityWhileReplacingSelectedUserWithAnotherUser() {
        var f = new Fixture(); f.profileUser = "user-1";
        var before = Map.copyOf(f.accounts);
        assertThrows(TuneWeaveApiClient.TuneWeaveException.class, () -> f.auth.refreshSession(PLATFORM));
        assertEquals(before, f.accounts);
        assertEquals(0, f.newLoginWrites + f.rotationWrites);
    }

    @Test void candidateRotationRemainsInMemoryUntilEnrichedProfileIsValidated() {
        var f = new Fixture(); f.rotateCandidate = true; f.enrichProfile = true;
        assertEquals("https://example.test/a", f.login().avatarUrl());
        assertEquals("twc1_candidate_rotated", f.accounts.get("user-0"));
        assertEquals(1, f.newLoginWrites);
        assertEquals(0, f.rotationWrites);
    }
}
