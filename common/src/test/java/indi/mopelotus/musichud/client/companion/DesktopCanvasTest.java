package indi.mopelotus.musichud.client.companion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopCanvasTest {
    @Test void physicalSizeIncludesTransparentPaddingAtAnIndependentScale() {
        var canvas = DesktopCanvas.of(152, 52, 2);
        assertEquals(312, canvas.width());
        assertEquals(112, canvas.height());
        assertEquals(156, canvas.guiWidth());
        assertEquals(56, canvas.guiHeight());
        assertEquals(624, DesktopCanvas.of(152, 52, 4).width());
    }

    @Test void invalidConfigCannotAllocateOversizedOrWrappedGpuTargets() {
        for (int[] values : new int[][]{{0,52,2}, {-1,52,2}, {152,0,2}, {152,52,0},
                {152,52,5}, {8192,52,1}, {4000,4000,1}, {Integer.MAX_VALUE,52,2}}) {
            assertThrows(RuntimeException.class, () -> DesktopCanvas.of(values[0], values[1], values[2]));
        }
    }
}
