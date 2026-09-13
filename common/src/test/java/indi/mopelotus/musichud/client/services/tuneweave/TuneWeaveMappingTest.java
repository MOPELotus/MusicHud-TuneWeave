package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.utils.image.PlatformIconUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import indi.mopelotus.musichud.client.services.music.AccountScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuneWeaveMappingTest {
    @Test
    void stableIdsAreDeterministicAndPlatformScoped() {
        long netease = TuneWeaveIdentity.stableId(TuneWeavePlatform.NETEASE, "track:123");

        assertEquals(netease,
                TuneWeaveIdentity.stableId(TuneWeavePlatform.NETEASE, "track:123"));
        assertNotEquals(netease,
                TuneWeaveIdentity.stableId(TuneWeavePlatform.QQ, "track:123"));
    }

    @Test
    void referencesPreserveFavoriteAliasesAndProviderIds() {
        assertEquals(TuneWeavePlatform.BILIBILI,
                TuneWeaveReference.platform("bilibili:video:BV1example"));
        assertEquals(TuneWeavePlatform.QQ,
                TuneWeaveReference.platformOrDefault(
                        "account:favorite_tracks:qq", TuneWeavePlatform.NETEASE));
        assertEquals(TuneWeavePlatform.NETEASE,
                TuneWeaveReference.platformOrDefault("123", TuneWeavePlatform.NETEASE));
        assertEquals("cloud:123", TuneWeaveReference.id("netease:cloud:123"));
        assertThrows(IllegalArgumentException.class, () -> TuneWeaveReference.platform("missing"));
    }

    @Test
    void snapshotMergeKeepsExplicitFieldsAndFillsMissingFields() {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("title", "snapshot title");
        snapshot.addProperty("cover_url", "http://example.test/cover.jpg");
        JsonObject source = new JsonObject();
        source.addProperty("title", "explicit title");
        source.add("cover_url", JsonNull.INSTANCE);
        source.add("snapshot", snapshot);

        JsonObject merged = TuneWeaveJson.mergeSnapshot(source);

        assertEquals("explicit title", TuneWeaveJson.string(merged, "title"));
        assertEquals("https://example.test/cover.jpg",
                TuneWeaveJson.imageUrl(merged, "cover_url"));
    }

    @Test
    void listEnvelopeParsingAcceptsItemsAndDropsBlankStrings() {
        JsonArray items = new JsonArray();
        items.add("first");
        items.add("");
        items.add(JsonNull.INSTANCE);
        items.add("second");
        JsonObject envelope = new JsonObject();
        envelope.add("items", items);

        assertEquals(4, TuneWeaveJson.elements(envelope).size());
        assertEquals(List.of("first", "second"), TuneWeaveJson.stringList(items));
    }

    @Test
    void cloudMappingPreservesOwnerScopedSelector() {
        JsonObject track = new JsonObject();
        track.addProperty("ref", "netease:track:42");
        track.addProperty("name", "Cloud track");
        track.addProperty("duration_ms", 60_000);
        JsonObject cloud = new JsonObject();
        cloud.addProperty("ref", "netease:cloud:42");
        cloud.add("track", track);

        TuneWeaveCloudTrack mapped = new TuneWeaveEntityMapper(platform -> null)
                .toCloudTrack(TuneWeavePlatform.NETEASE, cloud);

        assertEquals("netease:cloud:42", mapped.track().getSourcePartRef());
        assertTrue(mapped.track().isCloudSource());
    }

    @Test
    void trackMappingKeepsSourceIdentityForDisplayAndPublicPlayback() {
        JsonObject track = new JsonObject();
        track.addProperty("ref", "qq:track:42");
        track.addProperty("kind", "track");
        track.addProperty("title", "Source title");
        track.addProperty("duration_ms", 60_000);
        MusicDetail mapped = new TuneWeaveEntityMapper(platform -> null)
                .toTrack(TuneWeavePlatform.QQ, track);

        assertEquals("qq:track:42", mapped.getSourceRef());
        assertEquals("track", mapped.getSourceKind());
        assertEquals("Source title", mapped.getName());
    }

    @Test
    void sourceReferenceSelectsCorrectPlatformIconIncludingAliases() {
        MusicDetail qq = MusicDetail.fromTuneWeave(1, "tencent:track:42", "track", "QQ", 1,
                indi.mopelotus.musichud.beans.music.Album.NONE, List.of());
        MusicDetail bili = MusicDetail.fromTuneWeave(2, "bili:video:BV1", "video", "Bili", 1,
                indi.mopelotus.musichud.beans.music.Album.NONE, List.of());
        assertEquals(TuneWeavePlatform.QQ, PlatformIconUtils.platform(qq));
        assertEquals(TuneWeavePlatform.BILIBILI, PlatformIconUtils.platform(bili));
        assertEquals(null, PlatformIconUtils.platform(MusicDetail.NONE));
    }

    @Test
    void mapperKeepsSameReferenceSeparatedByAccountScope() {
        AtomicReference<TuneWeaveSession> session = new AtomicReference<>(
                new TuneWeaveSession(TuneWeavePlatform.QQ, "user-a", "A", "", true));
        TuneWeaveEntityMapper mapper = new TuneWeaveEntityMapper(platform -> session.get());
        JsonObject raw = new JsonObject();
        raw.addProperty("ref", "qq:playlist:42");
        raw.addProperty("name", "A list");
        Playlist first = mapper.toPlaylist(TuneWeavePlatform.QQ, raw);
        session.set(new TuneWeaveSession(TuneWeavePlatform.QQ, "user-b", "B", "", true));
        raw.addProperty("name", "B list");
        Playlist second = mapper.toPlaylist(TuneWeavePlatform.QQ, raw);
        assertEquals("A list", mapper.playlist(AccountScope.fromSession(new TuneWeaveSession(
                TuneWeavePlatform.QQ, "user-a", "A", "", true)), "qq:playlist:42").getName());
        assertEquals("B list", mapper.playlist(AccountScope.fromSession(session.get()), "qq:playlist:42").getName());
        assertEquals(first.getId(), second.getId());
    }

    @Test
    void mapperSeparatesAlbumAndArtistReferencesByAccountScope() {
        AtomicReference<TuneWeaveSession> session = new AtomicReference<>(
                new TuneWeaveSession(TuneWeavePlatform.QQ, "a", "A", "", true));
        TuneWeaveEntityMapper mapper = new TuneWeaveEntityMapper(platform -> session.get());
        JsonObject album = new JsonObject(); album.addProperty("ref", "qq:album:1"); album.addProperty("name", "A album");
        JsonObject artist = new JsonObject(); artist.addProperty("ref", "qq:artist:1"); artist.addProperty("name", "A artist");
        mapper.toAlbum(TuneWeavePlatform.QQ, album); mapper.toArtist(TuneWeavePlatform.QQ, artist);
        AccountScope first = AccountScope.fromSession(session.get());
        session.set(new TuneWeaveSession(TuneWeavePlatform.QQ, "b", "B", "", true));
        album.addProperty("name", "B album"); artist.addProperty("name", "B artist");
        mapper.toAlbum(TuneWeavePlatform.QQ, album); mapper.toArtist(TuneWeavePlatform.QQ, artist);
        assertEquals("A album", mapper.album(first, "qq:album:1").getName());
        assertEquals("B album", mapper.album(AccountScope.fromSession(session.get()), "qq:album:1").getName());
        assertEquals("A artist", mapper.artist(first, "qq:artist:1").getName());
        assertEquals("B artist", mapper.artist(AccountScope.fromSession(session.get()), "qq:artist:1").getName());
    }

    @Test
    void mapperUsesAnonymousScopeWhenSessionIsMissing() {
        TuneWeaveEntityMapper mapper = new TuneWeaveEntityMapper(platform -> null);
        JsonObject raw = new JsonObject();
        raw.addProperty("ref", "qq:playlist:missing-session");
        raw.addProperty("name", "Anonymous list");
        Playlist playlist = mapper.toPlaylist(TuneWeavePlatform.QQ, raw);
        assertSame(playlist, mapper.playlist(AccountScope.ANONYMOUS, "qq:playlist:missing-session"));
    }

    @Test
    void accountPageDeduplicationRuleKeepsFirstReference() {
        JsonObject first = new JsonObject(); first.addProperty("ref", "qq:playlist:1"); first.addProperty("name", "first");
        JsonObject duplicate = new JsonObject(); duplicate.addProperty("ref", "qq:playlist:1"); duplicate.addProperty("name", "duplicate");
        java.util.LinkedHashMap<String, JsonObject> unique = new java.util.LinkedHashMap<>();
        for (JsonObject value : List.of(first, duplicate)) unique.putIfAbsent(value.get("ref").getAsString(), value);
        assertEquals("first", unique.get("qq:playlist:1").get("name").getAsString());
        assertEquals(1, unique.size());
    }
}
