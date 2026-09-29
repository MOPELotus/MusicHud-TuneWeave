package indi.mopelotus.musichud.client.ui.lyric;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class LyricHighlightCalculatorTest {
    @Test void UnevenAndZeroDurationBoundariesNeverSweepBackwardsOrFlashTheWholeLine() {
        var timings = new LinkedHashMap<Duration,Integer>();
        timings.put(Duration.ZERO,1); timings.put(Duration.ofMillis(3000),4);
        timings.put(Duration.ofMillis(3030),5); timings.put(Duration.ofMillis(8000),10);
        var line=LyricLine.builder().text("abcdefghij").startTime(Duration.ZERO).duration(Duration.ofSeconds(8))
                .wordByWord(true).phraseEndingOffsetMap(timings).build();
        var calculator=new LyricHighlightCalculator(line);
        float previous=0;
        for(int millis=0;millis<=8000;millis+=5) {
            var state=calculator.compute(Duration.ofMillis(millis));
            assertNotNull(state); assertTrue(Float.isFinite(state.offset()));
            assertTrue(state.offset()+.00001>=previous); assertTrue(state.offset()<=10.00001);
            previous=state.offset();
        }
        assertEquals(4,calculator.compute(Duration.ofMillis(3000)).offset(),.0001);
        assertEquals(5,calculator.compute(Duration.ofMillis(3030)).offset(),.0001);
        assertEquals(0,calculator.compute(Duration.ofSeconds(-5)).offset());
        assertEquals(10,calculator.compute(Duration.ofSeconds(20)).offset());
        assertTrue(calculator.compute(Duration.ofMillis(100)).offset()<4);
    }
    @Test void PlainLyricsHaveNoArtificialWordSweep() {
        var line=LyricLine.builder().text("plain").startTime(Duration.ZERO).duration(Duration.ofSeconds(4)).build();
        assertNull(new LyricHighlightCalculator(line).compute(Duration.ofSeconds(1)));
    }
}
