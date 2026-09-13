package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountRequestScopeTest {
    @Test void coldProfileIsInitializedBeforeMappingScopeAndReused() {
        var calls = new AtomicInteger();
        var fixture = fixture(new AtomicReference<>("a"), () -> { calls.incrementAndGet(); });
        Playlist result = fixture.requests.prepare(TuneWeavePlatform.BILIBILI, () -> fixture.mapper.toPlaylist(TuneWeavePlatform.BILIBILI,
                JsonParser.parseString("{\"ref\":\"bilibili:favorite:1\",\"name\":\"Folder\"}").getAsJsonObject())).get();
        assertEquals("bilibili:favorite:1", result.getSourceRef());
        assertEquals("1", fixture.mapper.accountScope(TuneWeavePlatform.BILIBILI).userId());
        fixture.requests.prepare(TuneWeavePlatform.BILIBILI, () -> true).get();
        assertEquals(1, calls.get());
    }

    @Test void queuedAccountChangeAndCacheClearStillCancelBeforeDataWork() {
        var credential = new AtomicReference<>("a"); var calls = new AtomicInteger();
        var fixture = fixture(credential, calls::incrementAndGet);
        var old = fixture.requests.prepare(TuneWeavePlatform.BILIBILI, () -> fail("Old data request ran"));
        credential.set("b"); assertThrows(CancellationException.class, old::get); assertEquals(0, calls.get());
        var cleared = fixture.requests.prepare(TuneWeavePlatform.BILIBILI, () -> fail("Cleared data request ran"));
        fixture.mapper.clear(); assertThrows(CancellationException.class, cleared::get); assertEquals(0, calls.get());
    }

    @Test void epochClearedWhileProfileLoadsDoesNotPublishData() {
        AtomicReference<TuneWeaveEntityMapper> mapper = new AtomicReference<>();
        var fixture = fixture(new AtomicReference<>("a"), () -> mapper.get().clear()); mapper.set(fixture.mapper);
        assertThrows(CancellationException.class, () -> fixture.requests.prepare(TuneWeavePlatform.BILIBILI,
                () -> fail("Data request ran after clearing scope")).get());
    }

    private record Fixture(TuneWeaveEntityMapper mapper, TuneWeaveAccountRequests requests) {}
    private static Fixture fixture(AtomicReference<String> credential, Runnable requestHook) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "bilibili";
                    case "getTuneWeaveCredential" -> credential.get();
                    default -> throw new AssertionError(method.getName());
                });
        var gateway = new TuneWeaveGateway(config, (b, m, p, q, body, credentials) -> {
            assertEquals("/v1/auth/session", p); requestHook.run();
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(
                    "{\"platform\":\"bilibili\",\"user_id\":\"1\",\"nickname\":\"User\",\"avatar_url\":\"https://example.org/avatar\",\"authenticated\":true}"), new JsonObject());
        });
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
        var entities = new TuneWeaveEntityMapper(sessions::get);
        return new Fixture(entities, new TuneWeaveAccountRequests(gateway, entities, new TuneWeaveAuthenticationService(gateway, sessions)));
    }
}
