package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationBetaContractTest {
    private static final class Fixture {
        final Map<String, String> credentials = new HashMap<>();
        final Queue<String> responses = new ArrayDeque<>();
        final List<String> paths = new ArrayList<>();
        final List<JsonObject> bodies = new ArrayList<>();
        Runnable beforeResponse = () -> {};
        final TuneWeaveGateway gateway;
        final TuneWeaveAuthenticationService auth;
        Fixture() {
            ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> "migu";
                        case "getTuneWeaveCredential" -> credentials.getOrDefault(args[0], "");
                        case "setTuneWeaveCredential" -> { credentials.put((String) args[0], (String) args[1]); yield null; }
                        case "clearTuneWeaveCredential" -> { credentials.remove(args[0]); yield null; }
                        case "save", "setDefaultMusicPlatform" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
            gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
                paths.add(path); bodies.add(body == null ? new JsonObject() : body.getAsJsonObject().deepCopy());
                if (path.contains("/challenges") || path.equals("/v1/auth/import")) assertTrue(sent.isEmpty());
                beforeResponse.run();
                return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(responses.remove()), new JsonObject());
            });
            auth = new TuneWeaveAuthenticationService(gateway, new HashMap<>());
        }
        void profile(String platform) { responses.add("{\"platform\":\"" + platform + "\",\"user_id\":\"42\",\"nickname\":\"User\",\"avatar_url\":\"https://example.test/a\",\"authenticated\":true}"); }
        void confirmed(String platform) { responses.add("{\"state\":\"confirmed\",\"caller_credential\":{\"format\":\"tuneweave_credential_v1\",\"platform\":\"" + platform + "\",\"value\":\"twc1_verified\"}}"); }
    }
    @Test void passwordChallengesUseDedicatedRouteAndOnlyCommitConfirmedCredentials() {
        for (var platform : List.of(TuneWeavePlatform.KUWO, TuneWeavePlatform.MIGU)) {
            Fixture f = new Fixture(); var attempt = f.auth.beginLogin(platform);
            f.responses.add("{\"transaction_id\":\"password/id\",\"state\":\"verification_required\",\"verification\":{\"method\":\"image\",\"image\":{\"image_data_url\":\"data:image/png;base64,c3ludGhldGlj\"}}}");
            var session = f.auth.startPasswordLogin(attempt,"username","account","synthetic-password");
            assertTrue(f.credentials.isEmpty()); assertFalse(session.toString().contains("synthetic-password"));
            assertEquals("client",f.bodies.getFirst().get("credential_mode").getAsString());
            assertFalse(f.bodies.getFirst().has("country_code")); assertFalse(f.bodies.getFirst().has("account"));
            f.responses.add("{\"state\":\"verification_required\",\"verification\":{\"method\":\"sms\",\"masked_destination\":\"138****0000\"}}");
            JsonObject image = JsonParser.parseString("{\"action\":\"submit_image\",\"answer\":\"9\",\"password\":\"synthetic-password\"}").getAsJsonObject();
            assertEquals("verification_required", f.auth.advancePasswordLogin(session,image).state());
            assertEquals("/v1/auth/password/challenges/password%2Fid/verify",f.paths.get(1)); assertTrue(f.credentials.isEmpty());
            f.confirmed(platform.apiName()); f.profile(platform.apiName());
            assertTrue(f.auth.advancePasswordLogin(session,JsonParser.parseString("{\"action\":\"submit_sms\",\"code\":\"123456\"}").getAsJsonObject()).profile().authenticated());
            assertEquals("twc1_verified",f.credentials.get(platform.apiName()));
            assertThrows(CancellationException.class,()->f.auth.advancePasswordLogin(session,image));
        }
    }
    @Test void cancelledAndSupersededPasswordRequestsCannotCommitLateResponse() {
        Fixture f = new Fixture(); var attempt = f.auth.beginLogin(TuneWeavePlatform.MIGU);
        f.confirmed("migu");
        f.beforeResponse = () -> f.auth.beginLogin(TuneWeavePlatform.MIGU);
        assertThrows(CancellationException.class,()->f.auth.startPasswordLogin(attempt,"phone","13800000000","synthetic-password"));
        assertTrue(f.credentials.isEmpty()); assertEquals(1,f.paths.size());
        f.beforeResponse = () -> {};
        var active = f.auth.beginLogin(TuneWeavePlatform.MIGU);
        f.responses.add("{\"transaction_id\":\"pending\",\"state\":\"verification_required\",\"verification\":{\"method\":\"sms\"}}");
        var pending = f.auth.startPasswordLogin(active,"email","user@example.test","synthetic-password");
        f.auth.cancelLogin(active);
        assertThrows(CancellationException.class,()->f.auth.advancePasswordLogin(pending,new JsonObject()));
        assertEquals(2,f.paths.size());
    }
    @Test void passwordRejectsMalformedOrUnfinishedCredentialBeforePersistence() {
        for (String response : List.of("{\"state\":\"confirmed\"}", "{\"state\":\"verification_required\"}",
                "{\"state\":\"verification_required\",\"caller_credential\":{\"value\":\"secret\"}}")) {
            Fixture f = new Fixture(); f.responses.add(response);
            var token = f.auth.beginLogin(TuneWeavePlatform.MIGU);
            assertThrows(RuntimeException.class,()->f.auth.startPasswordLogin(token,"username","a","secret"));
            assertTrue(f.credentials.isEmpty());
        }
    }
    @Test void imageContinuationPreservesTransactionAndDoesNotAuthenticateEarly() {
        Fixture f = new Fixture();
        f.responses.add("{\"transaction_id\":\"sms\",\"state\":\"verification_required\",\"verification\":{\"image_data_url\":\"data:image/jpeg;base64,c3ludGhldGlj\",\"answer_kind\":\"arithmetic\"}}");
        var attempt = f.auth.beginLogin(TuneWeavePlatform.MIGU);
        var sms = f.auth.startSmsLogin(attempt, "13800000000", "86");
        assertEquals("verification_required", sms.progress().state()); assertTrue(f.credentials.isEmpty());
        f.responses.add("{\"state\":\"waiting\"}");
        JsonObject answer = JsonParser.parseString("{\"action\":\"submit_image\",\"answer\":\"8\"}").getAsJsonObject();
        assertEquals("waiting", f.auth.advanceSmsLogin(sms, answer).state());
        assertTrue(f.credentials.isEmpty());
        f.confirmed("migu"); f.profile("migu");
        assertTrue(f.auth.verifySmsLogin(sms, "0123").authenticated());
        assertEquals("twc1_verified", f.credentials.get("migu"));
        assertEquals("/v1/auth/challenges/sms/verify", f.paths.get(1));
        assertThrows(CancellationException.class, () -> f.auth.advanceSmsLogin(sms, answer));
    }
    @Test void accountSelectionDoesNotAutomaticallyPickAUserAndCancelledTransactionCannotResume() {
        Fixture f = new Fixture(); f.responses.add("{\"transaction_id\":\"sms\",\"state\":\"waiting\"}");
        var attempt = f.auth.beginLogin(TuneWeavePlatform.KUGOU);
        var sms = f.auth.startSmsLogin(attempt, "13800000000", "86");
        f.responses.add("{\"state\":\"account_selection_required\",\"accounts\":[{\"user_id\":\"42\",\"nickname\":\"A\"},{\"user_id\":\"43\",\"nickname\":\"B\"}]}");
        JsonObject code = JsonParser.parseString("{\"code\":\"012345\"}").getAsJsonObject();
        var result = f.auth.advanceSmsLogin(sms, code);
        assertEquals(2, result.accounts().size()); assertEquals(2, f.paths.size()); assertTrue(f.credentials.isEmpty());
        f.auth.cancelLogin(attempt);
        assertThrows(CancellationException.class, () -> f.auth.advanceSmsLogin(sms, code)); assertEquals(2, f.paths.size());
    }
    @Test void importsOnlyPersistVerifiedOpaqueCredential() {
        Fixture f = new Fixture(); var attempt = f.auth.beginLogin(TuneWeavePlatform.SODA);
        f.confirmed("soda"); f.profile("soda");
        f.auth.importCredential(attempt, "sessionid_ss=synthetic");
        assertEquals("twc1_verified", f.credentials.get("soda"));
        assertFalse(f.credentials.toString().contains("sessionid"));
        assertEquals("client", f.bodies.getFirst().get("credential_mode").getAsString());
        assertFalse(f.bodies.getFirst().has("account"));
        assertThrows(IllegalArgumentException.class, () -> f.auth.importCredential(attempt, "a=b\r\nSecret: x"));
    }
    @Test void unknownStatesAndMalformedCandidateListsAreRejectedWithoutSecretsInDiagnostics() {
        for (String input : List.of("{\"state\":\"unknown\"}", "{\"state\":\"account_selection_required\",\"accounts\":[]}",
                "{\"state\":\"account_selection_required\",\"accounts\":[{\"user_id\":\"42\"},{\"user_id\":\"42\"}]}",
                "{\"state\":\"verification_required\",\"verification\":42}"))
            assertThrows(RuntimeException.class, () -> TuneWeaveLoginProgress.read(JsonParser.parseString(input).getAsJsonObject(), null));
        var result = TuneWeaveLoginProgress.read(JsonParser.parseString("{\"state\":\"browser_verification_required\",\"verification\":{\"url\":\"secret\"}}").getAsJsonObject(), null);
        assertFalse(result.toString().contains("secret")); result.verification().addProperty("url", "mutated");
        assertEquals("secret", result.verification().get("url").getAsString());
    }
}
