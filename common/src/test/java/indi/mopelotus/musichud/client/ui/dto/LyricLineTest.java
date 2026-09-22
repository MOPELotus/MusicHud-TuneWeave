package indi.mopelotus.musichud.client.ui.dto;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LyricLineTest {
    @Test void durableEnglishPhrasesKeepWordsTogetherAndLeaveSpacesBreakable() {
        var line = line("hello world", Map.of(Duration.ofSeconds(2), 11));
        line.parsePhrases();
        var spans = line.getPhrases().getFirst().spans();
        assertEquals(2, spans.size());
        assertEquals(10, line.getPhrases().getFirst().charCount());
        assertEquals(0, line.getSpannableString().getSpanStart(spans.get(0)));
        assertEquals(5, line.getSpannableString().getSpanEnd(spans.get(0)));
        assertEquals(6, line.getSpannableString().getSpanStart(spans.get(1)));
        assertEquals(11, line.getSpannableString().getSpanEnd(spans.get(1)));
    }

    @Test void surrogatePairsAreOneAnimatedCharacterAndCannotBeSplitByBadOffsets() {
        var timings = new LinkedHashMap<Duration, Integer>();
        timings.put(Duration.ofSeconds(1), 2); // Inside the emoji's surrogate pair.
        timings.put(Duration.ofSeconds(2), 4);
        var line = line("a😀b", timings);
        line.parsePhrases();
        assertEquals(1, line.getPhrases().size());
        assertEquals(3, line.getPhrases().getFirst().charCount());
        assertEquals(4, line.getPhrases().getFirst().endOffset());
    }

    @Test void invalidOffsetsAndTimesDoNotPoisonLaterValidPhrases() {
        var timings = new LinkedHashMap<Duration, Integer>();
        timings.put(null, 1);
        timings.put(Duration.ofMillis(100), -1);
        timings.put(Duration.ofMillis(200), 500);
        timings.put(Duration.ofMillis(300), null);
        timings.put(Duration.ofSeconds(2), 2);
        timings.put(Duration.ofSeconds(1), 3);
        timings.put(Duration.ofSeconds(4), 4);
        var line = line("abcd", timings);
        assertDoesNotThrow(line::parsePhrases);
        assertEquals(2, line.getPhrases().size());
        assertEquals(1, line.binarySearchPhraseIndex(Duration.ofSeconds(2)));
        assertEquals(2, line.binarySearchPhraseIndex(Duration.ofSeconds(4)));
        assertEquals(2000, line.getPhrases().getLast().durationMillis());
    }

    @Test void emptyAndExtremelyLongPhrasesRemainRenderable() {
        var empty = line(null, Map.of());
        empty.parsePhrases();
        assertFalse(empty.isWordByWord());
        assertEquals("", empty.getSpannableString().toString());
        var longLine = line("ok", Map.of(Duration.ofDays(300), 2));
        assertDoesNotThrow(longLine::parsePhrases);
        assertEquals(Integer.MAX_VALUE, longLine.getPhrases().getFirst().durationMillis());
    }

    private static LyricLine line(String text, Map<Duration, Integer> timings) {
        return LyricLine.builder().text(text).startTime(Duration.ZERO).duration(Duration.ofSeconds(4))
                .wordByWord(true).phraseEndingOffsetMap(timings).build();
    }
}
