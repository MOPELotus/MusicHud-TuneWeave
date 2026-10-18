package indi.mopelotus.musichud.client.utils.lyrics;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.beans.music.Lyric;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
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
    @Test
    void normalizesBothSourceAndTranslation() {
        var detail = MusicDetail.fromTuneWeave(1, "qq:test", "track", "album", 3000, null, List.of());
        detail.setLyricInfo(new LyricInfo(new Lyric("[00:00.00]  A,B\u00a0  📀 "),
                new Lyric("[00:00.00] C,D\t  E "), new Lyric(""), new Lyric("")));
        var line = FullLineLyricParser.parse(detail).getFirst();
        assertEquals("A, B 📀", line.getText());
        assertEquals("C, D E", line.getTranslatedText());
    }
}
