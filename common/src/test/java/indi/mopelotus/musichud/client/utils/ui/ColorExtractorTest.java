package indi.mopelotus.musichud.client.utils.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColorExtractorTest {
    @Test void opaqueBlackAndColorChannelsSurviveHistogramSampling() {
        var black = ColorExtractor.extractColors(3, 1, (x, y) -> 0xff000000);
        for (int value : new int[]{black.primary, black.secondary, black.bright, black.dark})
            assertEquals(0, value & 0xffffff);
        var red = ColorExtractor.extractColors(3, 1, (x, y) -> 0xffff0000);
        assertTrue((red.primary & 0xffffff) >>> 16 >= 240);
        assertEquals(0, red.primary & 0xffff);
        assertEquals(255, (red.primary >>> 24) + (red.secondary >>> 24) + (red.bright >>> 24) + (red.dark >>> 24));
    }

    @Test void invisiblePixelsDoNotCreateFalseThemeColors() {
        var expected = ColorExtractor.getDefaultColors();
        var actual = ColorExtractor.extractColors(4, 2, (x, y) -> 0x00ff0000);
        assertEquals(expected.primary, actual.primary);
        assertEquals(expected.secondary, actual.secondary);
        assertEquals(expected.bright, actual.bright);
        assertEquals(expected.dark, actual.dark);
    }

    @Test void hugeDimensionsUseOverflowSafeBoundedSampling() {
        java.util.concurrent.atomic.AtomicInteger samples = new java.util.concurrent.atomic.AtomicInteger();
        var actual = ColorExtractor.extractColors(65_536, 65_536, (x, y) -> {
            samples.incrementAndGet(); return 0xff0000ff;
        });
        assertTrue(samples.get() <= ColorExtractor.MAX_SAMPLES);
        assertTrue((actual.primary & 255) >= 240);
    }
    @Test
    void sharesUseExactAlphaBudgetAndKeepEveryThemeColorVisible() {
        int[] alpha = ColorExtractor.sharesToAlphaBytes(0.7f, 0.2f, 0.1f, 0f);
        assertEquals(255, java.util.Arrays.stream(alpha).sum());
        assertTrue(java.util.Arrays.stream(alpha).allMatch(value -> value >= 1 && value <= 255));
    }

    @Test
    void largestRemainderKeepsInputPriorityOnEqualShares() {
        int[] alpha = ColorExtractor.sharesToAlphaBytes(0.25f, 0.25f, 0.25f, 0.25f);
        assertArrayEquals(new int[]{64, 64, 64, 63}, alpha);
    }
}
