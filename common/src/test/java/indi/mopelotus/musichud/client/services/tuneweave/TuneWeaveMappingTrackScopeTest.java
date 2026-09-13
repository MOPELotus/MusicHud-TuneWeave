package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveMappingTrackScopeTest {
    @Test
    void scopedTrackIndexContainsOnlyPlayableTracks() {
        var session = new TuneWeaveSession(TuneWeavePlatform.QQ, "u", "U", "", true);
        var mapper = new TuneWeaveEntityMapper(platform -> session);
        var raw = new com.google.gson.JsonObject();
        raw.addProperty("ref", "qq:track:1");
        raw.addProperty("name", "partial");
        raw.addProperty("duration_ms", 0);
        mapper.toTrack(TuneWeavePlatform.QQ, raw);
        assertNull(mapper.track(AccountScope.fromSession(session), "qq:track:1"));
        raw.addProperty("duration_ms", 1000);
        MusicDetail complete = mapper.toTrack(TuneWeavePlatform.QQ, raw);
        assertSame(complete, mapper.track(AccountScope.fromSession(session), "qq:track:1"));
    }
}
