package indi.mopelotus.musichud.client.utils.ui;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphemeProgressTest {
    @Test void wholeZwjAndVariationClustersRetainUtf16TimeFractions() {
        for (String text : new String[]{"👩‍🎤", "☀️", "é", "😀"}) {
            var progress = new GraphemeProgress(text);
            for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                assertEquals(40 * fraction,
                        progress.interpolate(text.length() * fraction, i -> i == 0 ? 0 : 40), .0001f);
            }
        }
    }

    @Test void noPrefixMeasurementCutsInsideAnEmojiCluster() {
        var progress = new GraphemeProgress("A👩‍🎤B");
        Set<Integer> boundaries = Set.of(0, 1, 6, 7);
        for (float offset = 0; offset <= 7; offset += .125f) {
            progress.interpolate(offset, i -> {
                assertTrue(boundaries.contains(i), "Truncated cluster at " + i);
                return i;
            });
        }
        var interval = progress.at(3.5f);
        assertEquals(new GraphemeProgress.Interval(1, 6, .5f), interval);
    }

    @Test void asciiEmptyAndNonFiniteInputsStayBounded() {
        var ascii = new GraphemeProgress("Matrix");
        assertEquals(3.5f, ascii.interpolate(3.5f, i -> i));
        assertEquals(0, ascii.interpolate(Float.NaN, i -> i));
        assertEquals(0, ascii.interpolate(Float.NEGATIVE_INFINITY, i -> i));
        assertEquals(6, ascii.interpolate(Float.POSITIVE_INFINITY, i -> i));
        assertEquals(0, new GraphemeProgress("").interpolate(99, i -> i));
    }
}
