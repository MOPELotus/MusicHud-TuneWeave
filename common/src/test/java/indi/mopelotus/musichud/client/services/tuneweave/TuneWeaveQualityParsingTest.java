package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.music.Quality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TuneWeaveQualityParsingTest {
    @Test
    void alpha10QualityNamesMapToClientQualityValues() {
        JsonObject value = new JsonObject();
        value.addProperty("actual_quality", "spatial");
        assertEquals(Quality.JY_EFFECT, TuneWeavePlaybackService.parseQuality(value, "actual_quality"));
        value.addProperty("actual_quality", "surround");
        assertEquals(Quality.SKY, TuneWeavePlaybackService.parseQuality(value, "actual_quality"));
        value.addProperty("actual_quality", "master");
        assertEquals(Quality.JY_MASTER, TuneWeavePlaybackService.parseQuality(value, "actual_quality"));
        value.addProperty("actual_quality", "vivid");
        assertEquals(Quality.VIVID, TuneWeavePlaybackService.parseQuality(value, "actual_quality"));
        value.addProperty("actual_quality", "high");
        assertEquals(Quality.EX_HIGH, TuneWeavePlaybackService.parseQuality(value, "actual_quality"));
    }
}
