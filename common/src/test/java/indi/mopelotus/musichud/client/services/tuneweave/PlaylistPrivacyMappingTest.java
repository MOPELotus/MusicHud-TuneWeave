package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistPrivacyMappingTest {
    @Test void mapsPrivateFlagsBeforeSourceContextIsBuilt() {
        var mapper = new TuneWeaveEntityMapper(platform -> null);
        for (String metadata : new String[]{"{\"visibility\":\"private\"}", "{\"extensions\":{\"private\":true}}",
                "{\"extensions\":{\"privacy\":10}}"}) {
            var value = JsonParser.parseString(metadata).getAsJsonObject();
            value.addProperty("ref", "netease:private"); value.addProperty("name", "Secret");
            Playlist playlist = mapper.toPlaylist(TuneWeavePlatform.NETEASE, value);
            assertEquals(Privacy.PRIVATE, playlist.getPrivacy());
            assertFalse(PlaybackSource.from(playlist, "RANDOM").navigable());
        }
    }

    @Test void malformedPrivacyCannotBecomePublic() {
        for (String metadata : new String[]{"{\"visibility\":{}}", "{\"extensions\":[]}",
                "{\"extensions\":{\"private\":\"false\"}}", "{\"extensions\":{\"privacy\":0.5}}",
                "{\"extensions\":{\"privacy\":[]}}", "{\"visibility\":\"public\",\"extensions\":{\"private\":true}}",
                "{\"extensions\":{\"private\":false,\"privacy\":10}}"}) {
            assertEquals(Privacy.PRIVATE, TuneWeaveEntityMapper.playlistPrivacy(JsonParser.parseString(metadata).getAsJsonObject()));
        }
        for (String metadata : new String[]{"{}", "{\"visibility\":\"public\"}",
                "{\"extensions\":{\"private\":false}}", "{\"extensions\":{\"privacy\":0}}"}) {
            assertEquals(Privacy.PUBLIC, TuneWeaveEntityMapper.playlistPrivacy(JsonParser.parseString(metadata).getAsJsonObject()));
        }
    }
}
