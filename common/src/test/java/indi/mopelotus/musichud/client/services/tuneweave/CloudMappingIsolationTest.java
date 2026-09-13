package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonParser;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudMappingIsolationTest {
    @Test void malformedCloudSnapshotCannotMutateNoneSentinelOrNormalTrackCache() {
        var platform = TuneWeavePlatform.NETEASE;
        var mapper = new TuneWeaveEntityMapper(p -> null);
        boolean originalCloud = MusicDetail.NONE.isCloudSource();
        var bad = JsonParser.parseString("{\"ref\":\"netease:cloud:1\",\"track\":{}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> mapper.toCloudTrack(platform, bad));
        assertEquals(originalCloud, MusicDetail.NONE.isCloudSource());
        var track = JsonParser.parseString("{\"ref\":\"netease:1\",\"duration_ms\":60000}").getAsJsonObject();
        var normal = mapper.toTrack(platform, track);
        var cloud = bad.deepCopy(); cloud.add("track", track);
        var mapped = mapper.toCloudTrack(platform, cloud).track();
        assertTrue(mapped.isCloudSource()); assertFalse(normal.isCloudSource());
        assertSame(normal, mapper.track(mapper.accountScope(platform), "netease:1"));
    }
}
