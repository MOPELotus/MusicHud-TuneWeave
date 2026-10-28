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

class CachedAccountFlagTest {
    @Test void epochChangesBetweenCaptureAndExecutionReturnNeutralWithoutRunningOrRetrying() {
        var f = new Fixture();
        var calls = new AtomicInteger();
        f.snapshotHook.set(f.entities::clear);
        assertFalse(f.requests.readCachedFlag(() -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, calls.get(), "an invalidated cached read must not adopt the new epoch");
        assertTrue(f.requests.readCachedFlag(() -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
    }

    @Test void rapidAccountSwitchesRejectOldFavoriteFlagsAndLeaveCurrentReadsUsable() {
        var f = new Fixture();
        Playlist favorite = f.account.loadPlaylists(TuneWeavePlatform.NETEASE).getLikeList();
        assertTrue(f.requests.readCachedFlag(() -> f.account.isFavoritePlaylist(favorite)));
        assertTrue(f.requests.readCachedFlag(() -> f.account.supportsFavoriteIntelligence(favorite)));
        for (int i = 0; i < 20; i++) {
            String user = Integer.toString(i + 2);
            assertFalse(f.requests.readCachedFlag(() -> {
                boolean old = f.account.isFavoritePlaylist(favorite);
                f.sessions.put(TuneWeavePlatform.NETEASE,
                        new TuneWeaveSession(TuneWeavePlatform.NETEASE, user, "User", "", true));
                f.entities.clear();
                return old;
            }), "a true flag read before a switch must not escape its rejected scope");
            assertFalse(f.requests.readCachedFlag(() -> f.account.isFavoritePlaylist(favorite)),
                    "the next account must not inherit the old account's favorite reference");
            Playlist current = f.account.loadPlaylists(TuneWeavePlatform.NETEASE).getLikeList();
            assertTrue(f.requests.readCachedFlag(() -> f.account.isFavoritePlaylist(current)));
        }
    }

    @Test void credentialChangeAlsoReturnsNeutralWhileWorkerRequestsStillCancel() {
        var f = new Fixture();
        var oldWorker = f.gateway.capture(f.entities.capture(() -> fail("stale worker ran")));
        f.snapshotHook.set(() -> f.credential.set("rotated"));
        assertFalse(f.requests.readCachedFlag(() -> fail("stale flag ran")));
        assertThrows(CancellationException.class, oldWorker::get);
        var oldEpoch = f.gateway.capture(f.entities.capture(() -> fail("old epoch worker ran")));
        f.entities.clear();
        assertThrows(CancellationException.class, oldEpoch::get);
        assertTrue(f.requests.readCachedFlag(() -> true));
    }

    @Test void unrelatedFailuresAreNotSilenced() {
        var f = new Fixture();
        IllegalStateException failure = new IllegalStateException("broken cached value");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> f.requests.readCachedFlag(() -> { throw failure; })));
    }

    private static final class Fixture {
        final AtomicReference<String> credential = new AtomicReference<>("original");
        final AtomicReference<Runnable> snapshotHook = new AtomicReference<>();
        final Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
        final TuneWeaveGateway gateway;
        final TuneWeaveEntityMapper entities;
        final TuneWeaveAccountRequests requests;
        final TuneWeaveAccountService account;
        Fixture() {
            ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
                    new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> {
                            Runnable hook = snapshotHook.getAndSet(null); if (hook != null) hook.run();
                            yield "http://127.0.0.1:7832";
                        }
                        case "getDefaultMusicPlatform" -> "netease";
                        case "getTuneWeaveCredential" -> credential.get();
                        default -> throw new AssertionError(method.getName());
                    });
            gateway = new TuneWeaveGateway(config, (base, method, path, query, body, credentials) -> {
                if (path.equals("/v1/account/favorites/playlist")) return response("{\"ref\":\"netease:favorites\",\"name\":\"Favorites\"}");
                if (path.equals("/v1/account/playlists")) {
                    JsonObject meta = new JsonObject();
                    meta.add("pagination", JsonParser.parseString("{\"has_more\":false,\"next_offset\":0}"));
                    return new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonArray(), meta);
                }
                throw new AssertionError("Unexpected request " + path);
            });
            sessions.put(TuneWeavePlatform.NETEASE,
                    new TuneWeaveSession(TuneWeavePlatform.NETEASE, "1", "User", "", true));
            entities = new TuneWeaveEntityMapper(sessions::get);
            var authentication = new TuneWeaveAuthenticationService(gateway, sessions);
            requests = new TuneWeaveAccountRequests(gateway, entities, authentication);
            account = new TuneWeaveAccountService(gateway, authentication, entities);
        }
        private static TuneWeaveApiClient.TuneWeaveResponse response(String json) {
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(json), new JsonObject());
        }
    }
}
