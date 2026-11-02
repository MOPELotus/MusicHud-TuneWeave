package indi.mopelotus.musichud.client.ui.hud;

import indi.mopelotus.musichud.client.ui.hud.metadata.Layout;
import indi.mopelotus.musichud.client.ui.hud.metadata.HorizontalAlign;
import indi.mopelotus.musichud.client.ui.hud.metadata.VerticalAlign;
import indi.mopelotus.musichud.client.ui.hud.renderer.HudRenderContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudDesktopOriginTest {
    @Test void desktopIgnoresGameAnchorsAndOffsetsButPreservesChildGeometry() {
        var context = new HudRenderContext();
        try {
            var base = new Layout("Base", 900, -300, 152, 52, 8, HorizontalAlign.RIGHT, VerticalAlign.BOTTOM);
            var child = new Layout("Album", 5, 6, 40, 40, 4);
            child.setParent(base);
            context.setDesktopOrigin(true);
            assertEquals(new Layout.AbsolutePosition(2, 2), base.calcAbsolutePosition(context));
            assertEquals(new Layout.AbsolutePosition(7, 8), child.calcAbsolutePosition(context));
            assertEquals(900, base.getX());
            assertEquals(-300, base.getY());
            assertEquals(HorizontalAlign.RIGHT, base.getHorizontalAlign());
            // The normal game path still respects its own geometry.
            context.setDesktopOrigin(false);
            base = new Layout("Base", 13, 17, 152, 52, 8);
            assertEquals(new Layout.AbsolutePosition(13, 17), base.calcAbsolutePosition(context));
        } finally { context.setDesktopOrigin(false); }
    }
}
