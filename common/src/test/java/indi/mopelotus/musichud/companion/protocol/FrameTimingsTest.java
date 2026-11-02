package indi.mopelotus.musichud.companion.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameTimingsTest {
    @Test void windowIsBoundedAndResettable() {
        var timings = new FrameTimings();
        assertEquals(0, timings.percentile(.95));
        for (int i = 1; i <= 1200; i++) timings.add(i);
        assertEquals(900, timings.percentile(.5));
        assertEquals(1170, timings.percentile(.95));
        timings.add(Double.NaN);
        timings.add(-10);
        assertEquals(1170, timings.percentile(.95));
        timings.reset();
        assertEquals(0, timings.percentile(.5));
    }
}
