package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.Privacy;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveRecentHistory.*;
import static indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform.NETEASE;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveRecentHistoryTest {
    @Test void threeRoutesUseOnlySelectedCredentialAndCanonicalAccountEntities() {
        Fixture f = new Fixture();
        for (Kind kind : Kind.values()) {
            var page = f.service.load(NETEASE, kind);
            assertEquals(kind, page.kind());
            assertEquals(8L, page.total());
            assertEquals(Instant.parse("2024-01-01T00:00:00.123Z"), page.entries().getFirst().playedAt());
            assertEquals("Android", page.entries().getFirst().device().displayName());
            Object resource = page.entries().getFirst().resource();
            switch (kind) {
                case TRACKS -> {
                    MusicDetail track = assertInstanceOf(MusicDetail.class, resource);
                    assertEquals("netease:123", track.getSourceRef());
                    assertEquals(180123, track.getDurationMillis());
                    assertEquals("Artist", track.getArtists().getFirst().getName());
                    assertEquals("netease:7", track.getAlbum().getSourceRef());
                }
                case ALBUMS -> assertEquals("netease:123", assertInstanceOf(Album.class, resource).getSourceRef());
                case PLAYLISTS -> assertEquals("Owner", assertInstanceOf(Playlist.class, resource).getCreator().getNickname());
            }
        }
        assertEquals(List.of("/v1/capabilities", "/v1/auth/session", "/v1/account/history/tracks",
                "/v1/capabilities", "/v1/account/history/albums", "/v1/capabilities", "/v1/account/history/playlists"), f.paths);
        assertEquals("1", f.mapper.accountScope(NETEASE).userId());
    }

    @Test void duplicateRecordsAndPlatformOrderArePreservedWithoutPagination() {
        Fixture f = new Fixture();
        JsonObject first = entry(Kind.TRACKS), second = first.deepCopy();
        second.addProperty("played_at", "2023-12-31T23:59:59.999Z");
        JsonArray data = new JsonArray(); data.add(first); data.add(second); data.add(first.deepCopy());
        var response = response(data); response.meta().getAsJsonObject("pagination").add("total", JsonNull.INSTANCE);
        var page = f.service.parse(NETEASE, Kind.TRACKS, response);
        assertEquals(3, page.entries().size());
        assertNull(page.total());
        assertEquals(page.entries().get(0).playedAt(), page.entries().get(2).playedAt());
        assertEquals(Instant.parse("2023-12-31T23:59:59.999Z"), page.entries().get(1).playedAt());
        assertThrows(UnsupportedOperationException.class, () -> page.entries().clear());
    }

    @Test void missingTimeDeviceAndCreatorRemainUnknownAndEmptyHistoryIsSuccessful() {
        Fixture f = new Fixture();
        f.service.load(NETEASE, Kind.PLAYLISTS); // A known current account must not become the missing creator.
        JsonObject raw = entry(Kind.PLAYLISTS);
        raw.add("played_at", JsonNull.INSTANCE); raw.remove("device");
        raw.getAsJsonObject("playlist").remove("creator");
        var page = f.service.parse(NETEASE, Kind.PLAYLISTS, response(array(raw)));
        assertNull(page.entries().getFirst().playedAt());
        assertNull(page.entries().getFirst().device());
        assertSame(Profile.ANONYMOUS, ((Playlist) page.entries().getFirst().resource()).getCreator());
        assertTrue(f.service.parse(NETEASE, Kind.TRACKS, response(new JsonArray())).entries().isEmpty());
    }

    @Test void missingCredentialsAndUnsupportedCapabilitiesNeverReadPrivateHistory() {
        Fixture f = new Fixture();
        f.credentials.put(NETEASE, "");
        assertEquals("authentication_required", assertThrows(TuneWeaveException.class,
                () -> f.service.load(NETEASE, Kind.TRACKS)).getCode());
        assertTrue(f.paths.isEmpty());
        f.credentials.put(NETEASE, "account-a");
        f.supported.clear();
        for (Kind kind : Kind.values()) assertEquals("capability_not_supported", assertThrows(TuneWeaveException.class,
                () -> f.service.load(NETEASE, kind)).getCode());
        assertTrue(f.paths.stream().allMatch("/v1/capabilities"::equals));
        assertEquals("capability_not_supported", assertThrows(TuneWeaveException.class,
                () -> f.service.load(TuneWeavePlatform.QQ, Kind.TRACKS)).getCode());
    }

    @Test void capabilitiesAreIndependentAndUpstreamErrorsAreNotConvertedToEmptySuccess() {
        Fixture f = new Fixture(); f.supported.remove(Kind.ALBUMS);
        assertFalse(f.service.load(NETEASE, Kind.TRACKS).entries().isEmpty());
        assertThrows(TuneWeaveException.class, () -> f.service.load(NETEASE, Kind.ALBUMS));
        assertFalse(f.service.load(NETEASE, Kind.PLAYLISTS).entries().isEmpty());
        for (String code : List.of("authentication_required", "permission_denied", "rate_limited", "upstream_error", "upstream_timeout")) {
            TuneWeaveException failure = new TuneWeaveException("normalized", 502, code, false, JsonNull.INSTANCE);
            f.onHistory = () -> { throw failure; };
            assertSame(failure, assertThrows(TuneWeaveException.class, () -> f.service.load(NETEASE, Kind.TRACKS)));
        }
    }

    @Test void accountChangesDuringDiscoveryAndHistoryRejectStaleResults() {
        Fixture f = new Fixture();
        f.onCapabilities = () -> f.credentials.put(NETEASE, "account-b");
        assertThrows(CancellationException.class, () -> f.service.load(NETEASE, Kind.TRACKS));
        assertEquals(List.of("/v1/capabilities"), f.paths);
        f.onCapabilities = () -> {};
        f.onHistory = () -> f.credentials.put(NETEASE, "account-c");
        assertThrows(CancellationException.class, () -> f.service.load(NETEASE, Kind.TRACKS));
        assertNull(f.mapper.track(f.mapper.accountScope(NETEASE), "netease:123"));
    }

    @Test void entityInvalidationDuringDiscoveryCannotAdoptANewerMappingEpoch() {
        Fixture f = new Fixture();
        f.onCapabilities = f.mapper::clear;
        assertThrows(CancellationException.class, () -> f.service.load(NETEASE, Kind.PLAYLISTS));
        assertEquals(List.of("/v1/capabilities"), f.paths);
    }

    @Test void expiredSessionIsReportedAsLoginRequiredWithoutReadingHistory() {
        Fixture f = new Fixture(); f.authenticated = false;
        assertEquals("authentication_required", assertThrows(TuneWeaveException.class,
                () -> f.service.load(NETEASE, Kind.TRACKS)).getCode());
        assertEquals(List.of("/v1/capabilities", "/v1/auth/session"), f.paths);
    }

    @Test void malformedIdentityAndTimestampFailTheWholeResponseBeforeAnyEntityMapping() {
        List<Consumer<JsonObject>> invalid = List.of(
                e -> e.add("track", JsonNull.INSTANCE),
                e -> e.getAsJsonObject("track").addProperty("ref", "qq:123"),
                e -> e.getAsJsonObject("track").addProperty("id", "999"),
                e -> e.getAsJsonObject("track").addProperty("id", 123),
                e -> e.getAsJsonObject("track").addProperty("platform", "qq"),
                e -> e.getAsJsonObject("track").addProperty("name", " "),
                e -> e.getAsJsonObject("track").addProperty("duration_ms", -1),
                e -> e.getAsJsonObject("track").addProperty("duration_ms", 2147483648L),
                e -> e.getAsJsonObject("track").addProperty("artists", "invalid"),
                e -> e.getAsJsonObject("track").getAsJsonObject("album").addProperty("ref", "qq:7"),
                e -> e.addProperty("played_at", 1704067200123L),
                e -> e.addProperty("played_at", "not-a-date"),
                e -> e.addProperty("played_at", "2024-02-30T00:00:00Z"),
                e -> e.addProperty("played_at", "2024-01-01T00:00:00"),
                e -> e.addProperty("device", false),
                e -> e.getAsJsonObject("device").addProperty("name", true));
        for (var edit : invalid) {
            Fixture f = new Fixture();
            JsonObject bad = entry(Kind.TRACKS); edit.accept(bad);
            JsonArray data = array(entry(Kind.TRACKS)); data.add(bad);
            assertThrows(TuneWeaveException.class, () -> f.service.parse(NETEASE, Kind.TRACKS, response(data)));
            assertNull(f.mapper.track(f.mapper.accountScope(NETEASE), "netease:123"));
        }
    }

    @Test void malformedPaginationAndOversizedWindowAreRejectedWithoutAnotherRequest() {
        for (Consumer<JsonObject> change : List.<Consumer<JsonObject>>of(
                p -> p.addProperty("limit", 300), p -> p.addProperty("offset", 1),
                p -> p.addProperty("next_offset", 100), p -> p.addProperty("has_more", true),
                p -> p.addProperty("has_more", "false"), p -> p.addProperty("total", -1),
                p -> p.addProperty("total", "8"), p -> p.addProperty("total", 1.5),
                p -> p.addProperty("total", new java.math.BigInteger("9223372036854775808")),
                p -> p.remove("extensions"), p -> p.getAsJsonObject("extensions").addProperty("continuation_supported", true))) {
            Fixture f = new Fixture(); var response = response(array(entry(Kind.TRACKS)));
            change.accept(response.meta().getAsJsonObject("pagination"));
            assertThrows(TuneWeaveException.class, () -> f.service.parse(NETEASE, Kind.TRACKS, response));
        }
        Fixture f = new Fixture(); JsonArray huge = new JsonArray();
        for (int i = 0; i < 101; i++) huge.add(entry(Kind.TRACKS));
        assertThrows(TuneWeaveException.class, () -> f.service.parse(NETEASE, Kind.TRACKS, response(huge)));
        assertThrows(TuneWeaveException.class, () -> f.service.parse(NETEASE, Kind.TRACKS, response(new JsonObject())));
        assertTrue(f.paths.isEmpty());
    }

    @Test void offsetTimestampsPreserveTheInstantAndDeviceFallsBackToOperatingSystem() {
        Fixture f = new Fixture(); JsonObject raw = entry(Kind.ALBUMS);
        raw.addProperty("played_at", "2024-01-01T08:00:00.123+08:00");
        raw.getAsJsonObject("device").remove("name");
        var entry = f.service.parse(NETEASE, Kind.ALBUMS, response(array(raw))).entries().getFirst();
        assertEquals(Instant.parse("2024-01-01T00:00:00.123Z"), entry.playedAt());
        assertEquals("android", entry.device().displayName());
    }

    @Test void unknownSnapshotsAndProviderFieldsCannotChangeCanonicalTrackMapping() {
        Fixture f = new Fixture(); JsonObject raw = entry(Kind.TRACKS);
        JsonObject track = raw.getAsJsonObject("track");
        track.remove("duration_ms"); track.remove("album");
        track.addProperty("kind", "video");
        track.add("snapshot", JsonParser.parseString("""
                {"duration_ms":-1,"album":{"ref":"qq:7","name":"Wrong platform"},"kind":"video"}
                """));
        JsonObject artist = track.getAsJsonArray("artists").get(0).getAsJsonObject();
        artist.addProperty("album_count", "not a number"); artist.add("avatar_url", new JsonObject());
        var mapped = (MusicDetail) f.service.parse(NETEASE, Kind.TRACKS, response(array(raw)))
                .entries().getFirst().resource();
        assertEquals("track", mapped.getSourceKind());
        assertEquals(0, mapped.getDurationMillis());
        assertNotEquals("qq:7", mapped.getAlbum().getSourceRef());
        assertEquals("Artist", mapped.getArtists().getFirst().getName());
        // Unknown duration is a summary, not a complete track eligible for scoped resolution reuse.
        assertNull(f.mapper.track(f.mapper.accountScope(NETEASE), "netease:123"));
    }

    @Test void canonicalProjectionRetainsPlaylistPrivacyIncludingMalformedHints() {
        for (String hints : List.of("{\"visibility\":\"private\"}", "{\"visibility\":true}",
                "{\"extensions\":{\"private\":true}}", "{\"extensions\":{\"privacy\":10}}",
                "{\"extensions\":false}")) {
            Fixture f = new Fixture(); JsonObject raw = entry(Kind.PLAYLISTS);
            JsonParser.parseString(hints).getAsJsonObject().entrySet()
                    .forEach(field -> raw.getAsJsonObject("playlist").add(field.getKey(), field.getValue()));
            var playlist = (Playlist) f.service.parse(NETEASE, Kind.PLAYLISTS, response(array(raw)))
                    .entries().getFirst().resource();
            assertEquals(Privacy.PRIVATE, playlist.getPrivacy());
        }
    }

    private static JsonObject entry(Kind kind) {
        JsonObject resource = JsonParser.parseString("""
                {"ref":"netease:123","platform":"netease","id":"123","name":"Example",
                 "duration_ms":180123,"track_count":12,"cover_url":"https://example.test/cover.jpg",
                 "artists":[{"ref":"netease:8","name":"Artist"}],
                 "album":{"ref":"netease:7","name":"Album"},"creator":{"ref":"netease:9","name":"Owner"}}
                """).getAsJsonObject();
        JsonObject entry = JsonParser.parseString("""
                {"played_at":"2024-01-01T00:00:00.123Z","device":{"name":"Android","operating_system":"android"}}
                """).getAsJsonObject();
        entry.add(kind.field(), resource);
        return entry;
    }
    private static JsonArray array(JsonElement value) { JsonArray result = new JsonArray(); result.add(value); return result; }
    private static TuneWeaveResponse response(JsonElement data) {
        return new TuneWeaveResponse(200, data, JsonParser.parseString("""
                {"pagination":{"limit":100,"offset":0,"total":8,"next_offset":null,"has_more":false,
                 "extensions":{"continuation_supported":false,"limit_applied":true}}}
                """).getAsJsonObject());
    }
    private static final class Fixture {
        final Map<TuneWeavePlatform, String> credentials = new EnumMap<>(TuneWeavePlatform.class);
        final List<String> paths = new ArrayList<>();
        final EnumSet<Kind> supported = EnumSet.allOf(Kind.class);
        Runnable onCapabilities = () -> {}, onHistory = () -> {};
        boolean authenticated = true;
        final TuneWeaveEntityMapper mapper;
        final TuneWeaveRecentHistoryService service;
        Fixture() {
            credentials.put(NETEASE, "account-a"); credentials.put(TuneWeavePlatform.QQ, "qq-account");
            ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> "netease";
                        case "getTuneWeaveCredential" -> credentials.getOrDefault(TuneWeavePlatform.fromApiName((String) args[0]), "");
                        default -> throw new AssertionError(method.getName());
                    });
            TuneWeaveGateway gateway = new TuneWeaveGateway(config, (base, method, path, query, body, headers) -> {
                paths.add(path); assertEquals("GET", method); assertNull(body);
                assertFalse(query.containsKey("account"));
                if (path.equals("/v1/capabilities")) {
                    assertTrue(headers.isEmpty()); onCapabilities.run();
                    JsonObject descriptor = new JsonObject(); descriptor.addProperty("platform", "netease");
                    descriptor.addProperty("registered", true); JsonArray capabilities = new JsonArray();
                    supported.forEach(kind -> capabilities.add(kind.capability())); descriptor.add("capabilities", capabilities);
                    return new TuneWeaveResponse(200, array(descriptor), new JsonObject());
                }
                assertEquals(List.of(credentials.get(NETEASE)), headers);
                if (path.equals("/v1/auth/session")) {
                    JsonObject session = JsonParser.parseString("""
                            {"platform":"netease","user_id":"1","nickname":"User","avatar_url":"https://example.test/user.jpg"}
                            """).getAsJsonObject();
                    session.addProperty("authenticated", authenticated);
                    return new TuneWeaveResponse(200, session, new JsonObject());
                }
                assertEquals(Map.of("platform", "netease", "limit", "100", "offset", "0"), query);
                onHistory.run();
                Kind kind = Arrays.stream(Kind.values()).filter(k -> path.endsWith("/" + k.path())).findFirst().orElseThrow();
                return response(array(entry(kind)));
            });
            Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
            mapper = new TuneWeaveEntityMapper(sessions::get);
            var accounts = new TuneWeaveAccountRequests(gateway, mapper, new TuneWeaveAuthenticationService(gateway, sessions));
            service = new TuneWeaveRecentHistoryService(gateway, mapper, accounts);
        }
    }
}
