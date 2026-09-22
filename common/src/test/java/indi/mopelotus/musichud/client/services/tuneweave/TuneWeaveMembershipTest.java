package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveMembershipTest {
    @Test void unknownAndMalformedStatusNeverBecomeAnActiveMembership() {
        var knownLevel = TuneWeaveMembership.parse(JsonParser.parseString("{\"level\":7,\"active\":null}"));
        assertEquals(7L, knownLevel.level()); assertNull(knownLevel.active());
        for (String value : List.of("{}", "null", "{\"level\":-1,\"active\":\"true\"}",
                "{\"level\":1.5,\"active\":1}", "{\"level\":4294967296,\"icon_url\":[]}")) {
            var result = TuneWeaveMembership.parse(JsonParser.parseString(value));
            assertNull(result.level()); assertNull(result.active()); assertEquals("", result.iconUrl());
        }
    }

    @Test void membershipUsesClientCredentialAndRejectsAnAccountSwitchDuringTheResponse() {
        var credential = new AtomicReference<>("account-a");
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> credential.get();
                    default -> throw new AssertionError(method.getName());
                });
        var hook = new AtomicReference<Runnable>(() -> {});
        var gateway = new TuneWeaveGateway(config, (base, method, path, query, body, sent) -> {
            assertEquals("/v1/account/membership", path);
            assertEquals("GET", method); assertEquals("netease", query.get("platform"));
            assertEquals("client", query.get("backend"));
            assertEquals(List.of("account-a"), sent);
            hook.get().run();
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString("{\"level\":3,\"active\":true}"), new JsonObject());
        });
        var sessions = new EnumMap<TuneWeavePlatform, TuneWeaveSession>(TuneWeavePlatform.class);
        sessions.put(TuneWeavePlatform.NETEASE, new TuneWeaveSession(TuneWeavePlatform.NETEASE, "1", "User", "", true));
        var mapper = new TuneWeaveEntityMapper(sessions::get);
        var auth = new TuneWeaveAuthenticationService(gateway, sessions);
        var requests = new TuneWeaveAccountRequests(gateway, mapper, auth);
        var account = new TuneWeaveAccountService(gateway, auth, mapper);
        assertEquals(3L, requests.prepare(TuneWeavePlatform.NETEASE, () -> account.loadMembership(TuneWeavePlatform.NETEASE)).get().level());
        var pending = requests.prepare(TuneWeavePlatform.NETEASE, () -> account.loadMembership(TuneWeavePlatform.NETEASE));
        hook.set(() -> credential.set("account-b"));
        assertThrows(CancellationException.class, pending::get);
    }
}
