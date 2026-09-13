package indi.mopelotus.musichud.client.utils.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LyricMotionTest {
    @Test
    void finishedPhraseStartsRaisedAndSettlesOnBaseline() {
        assertEquals(-2f, LyricMotion.lowerOffset(2, 0));
        assertEquals(0f, LyricMotion.lowerOffset(2, 1000));
    }

    @Test
    void playbackTimeOutsideAnimationStaysAtTheCorrespondingEndpoint() {
        assertEquals(-6f, LyricMotion.lowerOffset(6, Long.MIN_VALUE));
        assertEquals(0f, LyricMotion.lowerOffset(6, Long.MAX_VALUE));
    }

    @Test
    void loweringMovesTowardBaselineAtEveryGuiScale() {
        for (float height : new float[]{1, 2, 4, 6}) {
            float previous = -height;
            for (long elapsed = 0; elapsed <= 1000; elapsed += 10) {
                float current = LyricMotion.lowerOffset(height, elapsed);
                assertTrue(current >= previous && current <= 0, "Phrase must descend toward its baseline");
                previous = current;
            }
        }
    }

    @Test
    void rapidlyFinishingPhrasesKeepIndependentProgress() {
        float first = LyricMotion.lowerOffset(2, 250);
        assertEquals(-2f, LyricMotion.lowerOffset(2, 0));
        assertEquals(first, LyricMotion.lowerOffset(2, 250));
        assertTrue(first > -2 && first < 0);
        assertEquals(0f, LyricMotion.lowerOffset(2, 1500));
        assertEquals(-2f, LyricMotion.lowerOffset(2, -1));
    }
}
