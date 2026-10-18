package indi.mopelotus.musichud.client.ui.screen;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LyricsPanelPolicyTest {
    @Test void hidesInstrumentalAndClearedSnapshotsBetweenVocalTracks() {
        var normal = LyricLine.builder().type(LyricLine.Type.NORMAL).text("line").build();
        var meta = LyricLine.builder().type(LyricLine.Type.META_DATA).text("meta").build();
        var rhythm = LyricLine.builder().type(LyricLine.Type.RHYTHM).text("").build();
        assertFalse(LyricsPanelPolicy.hasLyrics(null));
        assertFalse(LyricsPanelPolicy.hasLyrics(List.of(normal, meta, rhythm)));
        assertTrue(LyricsPanelPolicy.hasLyrics(List.of(normal, normal)));
        assertFalse(LyricsPanelPolicy.hasLyrics(List.of()));
        assertTrue(LyricsPanelPolicy.hasLyrics(List.of(normal, meta, normal)));
    }
}
