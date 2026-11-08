package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.utils.LegacyDataMigration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalUniFailureIsolationTest {
    @TempDir Path directory;
    private Path previous;
    @BeforeEach void isolate() { previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(directory); }
    @AfterEach void restore() { MusicHud.setConfigDirectory(previous); }
    private Path file() { return LegacyDataMigration.uniPlaylists(directory); }
    private JsonObject track(int id) {
        return TuneWeaveUniPlaylistService.materializeLocalTrack(MusicDetail.fromTuneWeave(id, "qq:" + id,
                "track", "Track " + id, 1000, Album.NONE, List.of()), 0);
    }

    @Test void corruptSiblingsAreHiddenButSurviveUnrelatedEditsAndVisibleItemReordering() throws Exception {
        var initial = new LocalUniPlaylistStore();
        var document = initial.create("Healthy", "");
        String ref = "local:" + document.get("id").getAsString();
        initial.append(ref, List.of(track(1), track(2)));
        var database = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        var brokenDocument = JsonParser.parseString("{\"format\":\"broken\",\"unrelated\":\"keep exactly\"}");
        var brokenItem = JsonParser.parseString("{\"id\":\"broken\",\"kind\":\"unknown\",\"original\":\"keep exactly\"}");
        database.getAsJsonArray("playlists").add(brokenDocument.deepCopy());
        database.getAsJsonArray("playlists").get(0).getAsJsonObject().getAsJsonArray("items").add(brokenItem.deepCopy());
        Files.writeString(file(), database.toString());
        var store = new LocalUniPlaylistStore();
        assertEquals(1, store.list().size());
        assertEquals(2, store.items(ref).size());
        var ids = store.items(ref).stream().map(item -> item.get("id").getAsString()).toList();
        store.reorder(ref, List.of(ids.get(1), ids.get(0)));
        assertEquals(List.of("qq:2", "qq:1"), store.items(ref).stream().map(item -> item.get("source_ref").getAsString()).toList());
        store.create("Another healthy playlist", "");
        var saved = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        assertTrue(saved.getAsJsonArray("playlists").asList().contains(brokenDocument));
        assertTrue(saved.getAsJsonArray("playlists").get(0).getAsJsonObject().getAsJsonArray("items").asList().contains(brokenItem));
        assertEquals(2, new LocalUniPlaylistStore().list().size());
        assertEquals(2, store.exportDocument(ref).getAsJsonArray("items").size());
    }

    @Test void mixedAppendAndImportKeepValidItemsWithoutPersistingRejectedInput() {
        var store = new LocalUniPlaylistStore();
        var document = store.create("Mixed", "");
        String ref = "local:" + document.get("id").getAsString();
        var bad = track(9); bad.addProperty("credential", "must-not-be-imported");
        store.append(ref, List.of(track(1), bad, track(2)));
        assertEquals(List.of("qq:1", "qq:2"), store.items(ref).stream().map(item -> item.get("source_ref").getAsString()).toList());
        var exported = store.exportDocument(ref); exported.getAsJsonArray("items").add(bad);
        var original = exported.deepCopy();
        var imported = store.importDocument(exported);
        assertEquals(2, imported.getAsJsonArray("items").size());
        assertFalse(imported.toString().contains("credential"));
        assertEquals(original, exported);
    }

    @Test void malformedDatabaseEnvelopeIsNeverRewritten() throws Exception {
        Files.createDirectories(file().getParent());
        String original = "{\"schema_version\":999,\"playlists\":[]}";
        Files.writeString(file(), original);
        assertThrows(IllegalStateException.class, () -> new LocalUniPlaylistStore().list());
        assertEquals(original, Files.readString(file()));
    }
}
