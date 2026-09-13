package indi.mopelotus.musichud.client.utils.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColorExtractorTest {
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
