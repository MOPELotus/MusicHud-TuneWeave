package indi.mopelotus.musichud.client.ui.hud.renderer.text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GradientSpecTest {
    @Test void windowTravelsCompletelyOffBothEndsWithoutEndpointJump() {
        int sung = 0xFFFFFFFF, pending = 0x3BFFFFFF;
        for (float band : new float[]{0.002f, 0.1f, 0.5f}) {
            var start = GradientSpec.window(sung, pending, -band, band);
            var end = GradientSpec.window(sung, pending, 1f + band, band);
            for (int x = 0; x <= 100; x++) {
                assertEquals(pending, start.sample(x / 100f));
                assertEquals(sung, end.sample(x / 100f));
            }
        }
    }

    @Test void sortsCopiesAndLastDuplicateWins() {
        float[] stops = {1f, 0.5f, 0f, 0.5f};
        int[] colors = {0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF808080};
        var gradient = GradientSpec.of(stops, colors);
        stops[2] = 100f;
        colors[3] = 0;
        assertEquals(0xFF808080, gradient.sample(0.5f));
        assertEquals(0xFF404040, gradient.sample(0.25f));
        assertEquals(0xFFFFFFFF, gradient.sample(2f));
    }

    @Test void globalOpacityMultipliesAndNeverWrapsArgb() {
        var gradient = GradientSpec.flat(0x80402010);
        assertEquals(0x40402010, gradient.sample(0.5f, 0.5f));
        assertEquals(0x80402010, gradient.sample(0f, 2f));
        assertEquals(0x00402010, gradient.sample(1f, -1f));
        assertEquals(0x00402010, gradient.sample(1f, Float.NaN));
    }

    @Test void malformedStopsAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> GradientSpec.of(new float[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> GradientSpec.of(new float[]{0}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> GradientSpec.of(new float[]{Float.NaN}, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> GradientSpec.of(new float[]{Float.POSITIVE_INFINITY}, new int[1]));
    }
}
