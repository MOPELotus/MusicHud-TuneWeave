package indi.mopelotus.musichud.client.ui.dto;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricGraphemesTest {
    @Test void emojiSequencesAndCombiningMarksRemainWhole() {
        String text = "A👩‍🎤☀️é🇨🇳👍🏽1️⃣B";
        var clusters = LyricGraphemes.visualOrder(text, 0, text.length());
        assertEquals(List.of("A", "👩‍🎤", "☀️", "é", "🇨🇳", "👍🏽", "1️⃣", "B"),
                clusters.stream().map(c -> text.substring(c.start(), c.end())).toList());
        assertEquals(List.of(0, 1, 4, 6, 8, 10, 12, 15),
                clusters.stream().map(LyricGraphemes.Cluster::codePointIndex).toList());
    }

    @Test void supplementaryCharactersKeepOriginalUtf16OffsetsAndCodePointTimes() {
        String text = "prefix A😀𠀀B suffix";
        int start = text.indexOf('A'), end = text.indexOf(" suffix");
        var clusters = LyricGraphemes.visualOrder(text, start, end);
        assertEquals(List.of("A", "😀", "𠀀", "B"),
                clusters.stream().map(c -> text.substring(c.start(), c.end())).toList());
        assertEquals(List.of(0, 1, 2, 3),
                clusters.stream().map(LyricGraphemes.Cluster::codePointIndex).toList());
        assertEquals(List.of(start, start + 1, start + 3, start + 5),
                clusters.stream().map(LyricGraphemes.Cluster::start).toList());
    }

    @Test void mixedBidiDrawsInVisualOrderWithoutChangingLogicalAnimationIndices() {
        String text = "A אבג B";
        var clusters = LyricGraphemes.visualOrder(text, 0, text.length());
        assertEquals("A גבא B", String.join("", clusters.stream()
                .map(c -> text.substring(c.start(), c.end())).toList()));
        assertEquals(List.of(0, 1, 4, 3, 2, 5, 6),
                clusters.stream().map(LyricGraphemes.Cluster::codePointIndex).toList());
        assertTrue(clusters.get(2).rtl());
        assertFalse(clusters.getFirst().rtl());
    }

    @Test void rtlCombiningMarksStayWithTheirBaseAndEmptySpansHaveNoRuns() {
        String text = "אְב";
        var clusters = LyricGraphemes.visualOrder(text, 0, text.length());
        assertEquals(List.of("ב", "אְ"), clusters.stream()
                .map(c -> text.substring(c.start(), c.end())).toList());
        assertEquals(List.of(2, 0), clusters.stream().map(LyricGraphemes.Cluster::codePointIndex).toList());
        assertTrue(clusters.stream().allMatch(LyricGraphemes.Cluster::rtl));
        assertEquals(List.of(), LyricGraphemes.visualOrder(text, 1, 1));
    }
}
