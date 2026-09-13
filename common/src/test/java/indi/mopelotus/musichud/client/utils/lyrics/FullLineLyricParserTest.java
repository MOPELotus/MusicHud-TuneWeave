package indi.mopelotus.musichud.client.utils.lyrics;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FullLineLyricParserTest {
    @Test
    void expandsMultipleTimestampsBeforeOneLine() {
        List<FullLineLyricParser.LyricLineMetaData> lines = new ArrayList<>();

        FullLineLyricParser.matchLine("[00:01.00][00:02.500]repeat", line -> {
            if (line.type() == LyricLine.Type.NORMAL) {
                lines.add(line);
            }
        });

        assertEquals(2, lines.size());
        assertEquals(Duration.ofSeconds(1), lines.get(0).startTime());
        assertEquals(Duration.ofMillis(2500), lines.get(1).startTime());
        assertEquals("repeat", lines.get(0).lyric());
        assertEquals("repeat", lines.get(1).lyric());
    }
}
