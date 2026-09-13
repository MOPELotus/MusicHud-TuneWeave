package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackSnapshotTest {
    @Test
    void copiesAndProtectsLyricsList() {
        LyricLine line = LyricLine.builder()
                .startTime(Duration.ZERO)
                .text("line")
                .build();
        List<LyricLine> lyrics = new ArrayList<>(List.of(line));

        NowPlayingInfo.PlaybackSnapshot snapshot = new NowPlayingInfo.PlaybackSnapshot(
                null, null, lyrics, null, null);
        lyrics.clear();

        assertEquals(List.of(line), snapshot.lyrics());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.lyrics().add(line));
    }
}
