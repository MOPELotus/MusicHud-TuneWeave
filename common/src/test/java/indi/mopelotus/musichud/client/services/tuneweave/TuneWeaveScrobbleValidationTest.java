package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.Quality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuneWeaveScrobbleValidationTest {
    @Test
    void enforcesAlphaTenMeasuredPlaybackBounds() {
        assertTrue(TuneWeavePlaybackService.isValidScrobble(1_000, 2_000, 320_000, Quality.LOSSLESS));
        assertFalse(TuneWeavePlaybackService.isValidScrobble(0, 2_000, 320_000, Quality.LOSSLESS));
        assertFalse(TuneWeavePlaybackService.isValidScrobble(2_001, 2_000, 320_000, Quality.LOSSLESS));
        assertFalse(TuneWeavePlaybackService.isValidScrobble(1_000, 2_000, 0, Quality.LOSSLESS));
        assertFalse(TuneWeavePlaybackService.isValidScrobble(1_000, 2_000, 320_000, Quality.NONE));
    }
}
