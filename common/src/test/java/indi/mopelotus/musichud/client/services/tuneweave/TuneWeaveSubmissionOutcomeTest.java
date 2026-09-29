package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveSubmissionOutcomeTest {
    @Test void submitAndRecordDeletionUseDistinctWritesAndKeepPublicationUnknown() {
        var calls = new ArrayList<String>();
        var response = new AtomicReference<>("{\"playlist_ref\":\"kuwo:123\",\"accepted\":true,\"metadata_updated\":false,\"published\":null}");
        var gateway = new TuneWeaveGateway(ExtendedPaginationTest.config(new AtomicReference<>("twc1_synthetic")), (b,m,p,q,body,credentials) -> {
            calls.add(m + " " + p);
            if (m.equals("POST")) assertEquals(new JsonObject(), body); else assertNull(body);
            assertEquals(java.util.List.of("twc1_synthetic"), credentials);
            return new TuneWeaveApiClient.TuneWeaveResponse(200, JsonParser.parseString(response.get()), new JsonObject());
        });
        var accepted = TuneWeavePersonalLibrary.submit(gateway, "kuwo:123", false);
        assertTrue(accepted.acknowledged()); assertNull(accepted.published()); assertNull(accepted.ownedPlaylistPresent());
        response.set("{\"playlist_ref\":\"kuwo:123\",\"confirmed\":true,\"changed\":true,\"removed_records\":2,\"owned_playlist_present\":true,\"published\":null}");
        var deleted = TuneWeavePersonalLibrary.submit(gateway, "kuwo:123", true);
        assertEquals(2, deleted.removedRecords()); assertTrue(deleted.ownedPlaylistPresent()); assertNull(deleted.published());
        assertEquals(java.util.List.of("POST /v1/playlists/kuwo%3A123/submission", "DELETE /v1/account/playlist-submissions/kuwo%3A123"), calls);
        for (String bad : java.util.List.of("migu:123", "kuwo:", "unknown:123"))
            assertThrows(RuntimeException.class, () -> TuneWeavePersonalLibrary.submit(gateway, bad, true));
        assertEquals(2, calls.size());
    }
    @Test void malformedAcknowledgementsCannotBeShownAsSuccess() {
        var data = JsonParser.parseString("{\"playlist_ref\":\"kuwo:1\",\"confirmed\":true,\"removed_records\":2,\"owned_playlist_present\":true,\"published\":null}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> TuneWeaveSubmissionOutcome.read(data,"kuwo:2",true));
        for (String malformed : java.util.List.of("-1", "1.5", "9223372036854775808", "\"2\"", "null")) {
            var copy=data.deepCopy();copy.add("removed_records",JsonParser.parseString(malformed));
            assertThrows(IllegalArgumentException.class, () -> TuneWeaveSubmissionOutcome.read(copy,"kuwo:1",true));
        }
        for (String field : java.util.List.of("confirmed", "owned_playlist_present", "published")) {
            var copy=data.deepCopy();copy.addProperty(field,"true");
            assertThrows(IllegalArgumentException.class, () -> TuneWeaveSubmissionOutcome.read(copy,"kuwo:1",true));
        }
    }
}
