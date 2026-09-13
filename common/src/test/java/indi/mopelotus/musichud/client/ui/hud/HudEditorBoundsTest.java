package indi.mopelotus.musichud.client.ui.hud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudEditorBoundsTest {
    @Test void convertsAnchorsToGuiCoordinates() {
        assertEquals(new HudEditorBounds(234, 228, 150, 52),
                HudEditorBounds.fromConfig(16, 20, 150, 52, "RIGHT", "BOTTOM", 400, 300));
        assertEquals(new HudEditorBounds(141, 144, 150, 52),
                HudEditorBounds.fromConfig(16, 20, 150, 52, "CENTER", "CENTER", 400, 300));
        for (String horizontal : new String[]{"LEFT", "CENTER", "RIGHT"}) {
            for (String vertical : new String[]{"TOP", "CENTER", "BOTTOM"}) {
                var bounds = HudEditorBounds.fromConfig(16, 20, 150, 52, horizontal, vertical, 400, 300);
                assertEquals(16, bounds.offsetX(horizontal, 400));
                assertEquals(20, bounds.offsetY(vertical, 300));
            }
        }
    }

    @Test void movementAndResizeStayInsideViewportAndRetainOriginalDraft() {
        var original = new HudEditorBounds(16, 16, 150, 52);
        assertEquals(new HudEditorBounds(250, 248, 150, 52), original.drag(9999, 9999, false, 400, 300));
        assertEquals(new HudEditorBounds(0, 0, 150, 52), original.drag(-9999, -9999, false, 400, 300));
        assertEquals(new HudEditorBounds(16, 16, 384, 256), original.drag(9999, 9999, true, 400, 300));
        assertEquals(new HudEditorBounds(16, 16, 16, 16), original.drag(-9999, -9999, true, 400, 300));
        assertEquals(150, original.width());
        assertEquals(original, original.drag(Double.NaN, 0, false, 400, 300));
        assertEquals(original, original.drag(0, Double.POSITIVE_INFINITY, true, 400, 300));
    }

    @Test void malformedSavedDimensionsAndSmallWindowClampSafely() {
        var clamped = new HudEditorBounds(-100, 99999, Integer.MAX_VALUE, -1).clamp(100, 40);
        assertEquals(new HudEditorBounds(0, 24, 100, 16), clamped);
        assertFalse(clamped.contains(Double.NaN, 24));
        assertTrue(clamped.contains(0, 24));
        assertFalse(clamped.contains(100, 24));
    }
}
