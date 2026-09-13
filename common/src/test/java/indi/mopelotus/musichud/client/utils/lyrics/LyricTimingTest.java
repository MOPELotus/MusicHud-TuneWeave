package indi.mopelotus.musichud.client.utils.lyrics;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LyricTimingTest {
    @Test void earlyScrollAndLateAttachDoNotShiftTheSungTimestamp() {
        Duration start = Duration.ofSeconds(10);
        assertEquals(0f, LyricTiming.highlightFraction(start.minusMillis(500), start, 300));
        assertEquals(0f, LyricTiming.highlightFraction(start.minusMillis(1), start, 300));
        assertEquals(0.5f, LyricTiming.highlightFraction(start.plusMillis(150), start, 300));
        assertEquals(1f, LyricTiming.highlightFraction(start.plusSeconds(2), start, 300));
        assertEquals(500, LyricTiming.delayMillis(start.minusMillis(500), start));
        assertEquals(0, LyricTiming.delayMillis(start.plusSeconds(2), start));
        assertThrows(IllegalArgumentException.class, () -> LyricTiming.highlightFraction(start, start, 0));
    }

    @Test void nearestTranslationTimestampUsesToleranceAndIgnoresPlaceholders() {
        Duration original = Duration.ofMillis(649);
        assertEquals(original, LyricTiming.nearest(Duration.ofMillis(640), List.of(original, Duration.ofMillis(1668)), 80));
        assertNull(LyricTiming.nearest(Duration.ofMillis(900), List.of(original), 80));
        assertTrue(LyricTiming.isPlaceholderTranslation("//"));
        assertTrue(LyricTiming.isPlaceholderTranslation("   "));
        assertFalse(LyricTiming.isPlaceholderTranslation("制作人"));
    }
}
