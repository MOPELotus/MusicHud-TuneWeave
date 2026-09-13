package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveRequestScopeTest {
    private static final TuneWeavePlatform QQ = TuneWeavePlatform.QQ;

    @Test void malformedOrWrongPlatformProfileIsNeverCached() {
        for (String json : java.util.List.of(
                "{\"platform\":\"netease\",\"user_id\":\"a\",\"authenticated\":true}",
                "{\"platform\":\"qq\",\"user_id\":\"a\",\"authenticated\":\"true\"}",
                "{\"platform\":\"qq\",\"authenticated\":true}")) {
            Map<TuneWeavePlatform, TuneWeaveSession> sessions = new java.util.HashMap<>();
            var gateway = new TuneWeaveGateway(config(new AtomicReference<>("credential"), new AtomicReference<>("qq")),
                    (base, method, path, query, body, sent) -> new TuneWeaveApiClient.TuneWeaveResponse(200,
                            com.google.gson.JsonParser.parseString(json), new JsonObject()));
            assertThrows(TuneWeaveApiClient.TuneWeaveException.class,
                    () -> new TuneWeaveAuthenticationService(gateway, sessions).loadSession(QQ));
            assertTrue(sessions.isEmpty());
        }
    }

    @Test void queuedMappingCannotAdoptNewAccount() {
        var session = new AtomicReference<>(session("a"));
        var mapper = new TuneWeaveEntityMapper(platform -> session.get());
        var load = mapper.capture(() -> mapper.toPlaylist(QQ, entity("qq:playlist:1", "A")));
        session.set(session("b"));
        assertThrows(CancellationException.class, load::get);
        assertNull(mapper.playlist(AccountScope.fromSession(session.get()), "qq:playlist:1"));
    }

    @Test void responseAfterAccountChangeCannotPopulateAnyEntityCache() {
        var session = new AtomicReference<>(session("a"));
        var mapper = new TuneWeaveEntityMapper(platform -> session.get());
        var old = mapper.capture(() -> {
            session.set(session("b"));
            mapper.clear();
            return mapper.toAlbum(QQ, entity("qq:album:1", "A"));
        });
        assertThrows(CancellationException.class, old::get);
        assertNull(mapper.album(AccountScope.fromSession(session.get()), "qq:album:1"));
        assertEquals("B", mapper.capture(() -> mapper.toAlbum(QQ, entity("qq:album:1", "B"))).get().getName());
    }

    @Test void numericLookupDoesNotFallBackToAnotherAccountsEntity() {
        var session = new AtomicReference<>(session("a"));
        var mapper = new TuneWeaveEntityMapper(platform -> session.get());
        var playlist = mapper.toPlaylist(QQ, entity("qq:playlist:1", "A"));
        session.set(session("b"));
        assertNull(mapper.playlist(playlist.getId()));
        assertFalse(mapper.hasPlaylist(playlist.getId()));
        var b = mapper.toPlaylist(QQ, entity("qq:playlist:1", "B"));
        session.set(session("a"));
        assertSame(playlist, mapper.playlist(b.getId()));
    }

    @Test void clearInvalidatesQueuedWorkAndAllReferenceAndNumericIndexes() {
        var mapper = new TuneWeaveEntityMapper(platform -> session("a"));
        var playlist = mapper.toPlaylist(QQ, entity("qq:playlist:1", "A"));
        var album = mapper.toAlbum(QQ, entity("qq:album:1", "A"));
        var artist = mapper.toArtist(QQ, entity("qq:artist:1", "A"));
        var old = mapper.capture(() -> mapper.toPlaylist(QQ, entity("qq:playlist:1", "old")));
        mapper.clear();
        assertFalse(mapper.hasPlaylist(playlist.getId()));
        assertFalse(mapper.hasAlbum(album.getId()));
        assertFalse(mapper.hasArtist(artist.getId()));
        assertThrows(CancellationException.class, old::get);
    }

    @Test void queuedTransportCannotUseReplacementCredential() {
        var credentials = new AtomicReference<>("test-account-a");
        var platform = new AtomicReference<>("qq");
        var gateway = gateway(credentials, platform);
        var operation = gateway.capture(() -> fail("Superseded work must not run"));
        credentials.set("test-account-b");
        assertThrows(CancellationException.class, operation::get);
        assertEquals("test-account-b", gateway.credential(QQ));
    }

    @Test void nestedTransportKeepsCapturedPlatformAndCleansUpAfterFailure() {
        var credentials = new AtomicReference<>("test-account-a");
        var platform = new AtomicReference<>("qq");
        var gateway = gateway(credentials, platform);
        var operation = gateway.capture(() -> gateway.capture(gateway::defaultPlatform).get());
        platform.set("netease");
        assertEquals(QQ, operation.get());
        assertEquals(TuneWeavePlatform.NETEASE, gateway.defaultPlatform());
        assertThrows(CancellationException.class, () -> gateway.capture(() -> {
            credentials.set("test-account-b");
            return gateway.credential(QQ);
        }).get());
        assertEquals("test-account-b", gateway.credential(QQ));
    }

    @Test void actualTransportUsesCapturedCredentialAndRejectsResponseAfterSwitch() {
        var credentials = new AtomicReference<>("test-account-a");
        var platform = new AtomicReference<>("qq");
        var gateway = new TuneWeaveGateway(config(credentials, platform), (base, method, path, query, body, sent) -> {
            assertEquals(java.util.List.of("test-account-a"), sent);
            credentials.set("test-account-b");
            return new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonArray(), new JsonObject());
        });
        assertThrows(CancellationException.class, () -> gateway.capture(() ->
                gateway.requestForPlatform(QQ, "GET", "/v1/account/playlists", Map.of(), null)).get());
    }

    @Test void lateLogoutCannotEraseNewLoginOrItsSessionProfile() {
        var credentials = new AtomicReference<>("test-account-a");
        var platform = new AtomicReference<>("qq");
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        sessions.put(QQ, session("a"));
        var gateway = new TuneWeaveGateway(config(credentials, platform), (base, method, path, query, body, sent) -> {
            credentials.set("test-account-b");
            sessions.put(QQ, session("b"));
            return new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonObject(), new JsonObject());
        });
        var authentication = new TuneWeaveAuthenticationService(gateway, sessions);
        assertThrows(CancellationException.class, authentication.prepareLogout(QQ)::run);
        assertEquals("test-account-b", credentials.get());
        assertEquals("b", sessions.get(QQ).userId());
    }

    @Test void failedLogoutClearsOnlyOriginalLocalCredentialAndProfile() {
        var credentials = new AtomicReference<>("test-account-a");
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        sessions.put(QQ, session("a"));
        var gateway = new TuneWeaveGateway(config(credentials, new AtomicReference<>("qq")),
                (base, method, path, query, body, sent) -> { throw new IllegalStateException("offline"); });
        var authentication = new TuneWeaveAuthenticationService(gateway, sessions);
        assertThrows(IllegalStateException.class, authentication.prepareLogout(QQ)::run);
        assertTrue(credentials.get().isEmpty());
        assertNull(sessions.get(QQ));
    }

    @Test void staleProfileCannotOverwriteNewAccount() {
        var credentials = new AtomicReference<>("test-account-a");
        Map<TuneWeavePlatform, TuneWeaveSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        sessions.put(QQ, session("a"));
        var gateway = new TuneWeaveGateway(config(credentials, new AtomicReference<>("qq")),
                (base, method, path, query, body, sent) -> {
                    credentials.set("test-account-b");
                    sessions.put(QQ, session("b"));
                    JsonObject profile = new JsonObject();
                    profile.addProperty("platform", "qq"); profile.addProperty("user_id", "a");
                    profile.addProperty("authenticated", true);
                    return new TuneWeaveApiClient.TuneWeaveResponse(200, profile, new JsonObject());
                });
        assertThrows(CancellationException.class, () -> new TuneWeaveAuthenticationService(gateway, sessions).loadSession(QQ));
        assertEquals("b", sessions.get(QQ).userId());
    }

    private static TuneWeaveGateway gateway(AtomicReference<String> credentials, AtomicReference<String> platform) {
        return new TuneWeaveGateway(config(credentials, platform));
    }

    private static ClientConfig config(AtomicReference<String> credentials, AtomicReference<String> platform) {
        return (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
                new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> platform.get();
                    case "getTuneWeaveCredential" -> credentials.get();
                    case "clearTuneWeaveCredential" -> { credentials.set(""); yield null; }
                    case "save" -> null;
                    default -> throw new AssertionError("Unexpected config method " + method.getName());
                });
    }

    private static TuneWeaveSession session(String id) { return new TuneWeaveSession(QQ, id, id, "", true); }
    private static JsonObject entity(String ref, String name) {
        JsonObject value = new JsonObject(); value.addProperty("ref", ref); value.addProperty("name", name); return value;
    }
}
