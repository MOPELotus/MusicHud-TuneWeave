package indi.mopelotus.musichud.client.ui.lyric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LineBlurRendererTest {
    @Test void focusStaysSharpAndDistanceSaturatesAtEveryGuiScale() {
        for (float radius : new float[]{0, 4, 8, 16, 32}) {
            assertEquals(0, LineBlurRenderer.radiusForDistance(0, radius));
            float previous = 0;
            for (int distance = 1; distance < 100; distance++) {
                float value = LineBlurRenderer.radiusForDistance(distance, radius);
                assertTrue(value >= previous && value <= radius); previous = value;
            }
            assertEquals(radius, previous);
            assertTrue(LineBlurRenderer.maxPaddingFor(radius) >= radius);
        }
    }
    @Test void invalidBlurValuesCannotAllocateUnboundedTargets() {
        for (float value : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> LineBlurRenderer.maxPaddingFor(value));
            assertThrows(IllegalArgumentException.class, () -> LineBlurRenderer.radiusForDistance(2, value));
        }
    }
}
