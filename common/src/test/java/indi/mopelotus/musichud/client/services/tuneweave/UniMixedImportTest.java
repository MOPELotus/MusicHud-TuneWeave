package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UniMixedImportTest {
    @Test void completeLocalAlbumNeverCallsRemoteMaterialization() {
        var tracks = new indi.mopelotus.musichud.utils.collections.ObservableSequencedSet<MusicDetail>();
        tracks.add(track("netease:1"));
        var album = new Album(4, "Local album", "", "", "", 1, tracks, new LinkedHashSet<>(), PusherInfo.EMPTY, "netease:album:4");
        var service = service((b, m, p, q, body, c) -> { throw new AssertionError("Unexpected remote materialization"); },
                ref -> null, ref -> album);
        var result = service.materializeImportSources(List.of(new TuneWeaveUniImportSource("netease", "album", "album:4")));
        assertEquals("Local album", result.name());
        assertEquals("netease:1", result.items().getFirst().get("source_ref").getAsString());
    }

    @Test void rejectsMalformedRemoteCountsBeforeReturningAnImport() {
        for (String count : List.of("null", "\"2\"", "1.5", "-1", "100001")) {
            var service = service((b, m, p, q, body, c) -> {
                JsonObject data = new JsonObject();
                data.add("items", new JsonArray());
                data.add("item_count", JsonParser.parseString(count));
                return new TuneWeaveApiClient.TuneWeaveResponse(200, data, new JsonObject());
            }, ref -> null);
            assertThrows(IllegalArgumentException.class, () -> service.materializeImportSources(
                    List.of(new TuneWeaveUniImportSource("netease", "playlist", "remote"))));
        }
    }

    @Test void mixesLocalAndDescriptorSourcesWithoutRematerializingLocalTracks() {
        List<String> sent = new ArrayList<>();
        var service = service((b, m, p, q, body, c) -> {
            var sources = body.getAsJsonObject().getAsJsonArray("sources");
            assertEquals(1, sources.size());
            sent.add(sources.get(0).getAsJsonObject().get("id").getAsString());
            JsonArray items = new JsonArray();
            items.add(TuneWeaveUniPlaylistService.materializeLocalTrack(track("netease:1"), 0));
            items.add(TuneWeaveUniPlaylistService.materializeLocalTrack(track("qq:2"), 1));
            JsonObject data = new JsonObject(); data.add("items", items); data.addProperty("item_count", 2);
            return new TuneWeaveApiClient.TuneWeaveResponse(200, data, new JsonObject());
        }, ref -> ref.equals("netease:local") ? playlist() : null);
        var result = service.materializeImportSources(List.of(new TuneWeaveUniImportSource("netease", "playlist", "local"),
                new TuneWeaveUniImportSource("qq", "album", "remote")));
        assertEquals(List.of("remote"), sent);
        assertEquals(List.of("netease:1", "qq:2"), result.items().stream().map(v -> v.get("source_ref").getAsString()).toList());
        assertEquals(1, result.items().get(1).get("position").getAsInt());
        assertEquals(1, result.items().get(1).getAsJsonObject("extensions").get("import_source_index").getAsInt());
        assertEquals("Local", result.name());
    }

    @Test void rejectsMalformedSourcesAndIncompleteRemoteOutput() {
        var service = service((b, m, p, q, body, c) -> {
            JsonObject data = new JsonObject(); data.add("items", new JsonArray()); data.addProperty("item_count", 2);
            return new TuneWeaveApiClient.TuneWeaveResponse(200, data, new JsonObject());
        }, ref -> null);
        assertThrows(IllegalArgumentException.class, () -> service.materializeImportSources(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.materializeImportSources(Collections.nCopies(51,
                new TuneWeaveUniImportSource("netease", "playlist", "remote"))));
        assertThrows(IllegalArgumentException.class, () -> service.materializeImportSources(
                List.of(new TuneWeaveUniImportSource("netease", "playlist", ""))));
        assertThrows(IllegalArgumentException.class, () -> service.materializeImportSources(
                List.of(new TuneWeaveUniImportSource("netease", "playlist", "remote"))));
    }

    private static TuneWeaveUniPlaylistService service(TuneWeaveGateway.Transport transport, java.util.function.Function<String, Playlist> known) {
        return service(transport, known, reference -> null);
    }

    private static TuneWeaveUniPlaylistService service(TuneWeaveGateway.Transport transport, java.util.function.Function<String, Playlist> known,
                                                      java.util.function.Function<String, Album> knownAlbum) {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch(method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> "test";
                    default -> throw new AssertionError(method.getName());
                });
        return new TuneWeaveUniPlaylistService(new TuneWeaveGateway(config, transport),
                new TuneWeaveEntityMapper(platform -> null), new LocalUniPlaylistStore(), known, knownAlbum);
    }
    private static Playlist playlist() {
        Playlist playlist = Playlist.fromTuneWeave(1, "netease:local", "Local", "", 1, 0, Profile.ANONYMOUS);
        playlist.getTracks().add(track("netease:1")); return playlist;
    }
    private static MusicDetail track(String ref) { return MusicDetail.fromTuneWeave(1, ref, "track", ref, 1000, Album.NONE, List.of()); }
}
