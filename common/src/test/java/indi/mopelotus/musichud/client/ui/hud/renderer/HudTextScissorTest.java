package indi.mopelotus.musichud.client.ui.hud.renderer;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.Font;
import indi.mopelotus.musichud.client.ui.hud.renderer.text.GradientSpec;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class HudTextScissorTest {
    @Test void fullyClippedProductionDrawDoesNotReachGeometryOrGuiSubmission() throws Exception {
        HudRenderContext previous = HudRenderContext.getCurrent();
        HudRenderContext context = new HudRenderContext() {
            @Override public int guiWidth() { return 720; }
            @Override public int guiHeight() { return 450; }
            @Override public Matrix3x2f currentPose() {
                throw new AssertionError("Fully clipped text must not build geometry");
            }
        };
        var stackField = HudRenderContext.class.getDeclaredField("scissorStack");
        stackField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Deque<ScreenRectangle> stack = (Deque<ScreenRectangle>) stackField.get(context);
        // A font without a provider and a context without graphics make any attempt to
        // prepare glyphs, submit them, or invoke the ordinary-text fallback fail this test.
        Font font = new Font(null);
        try {
            for (ScreenRectangle clip : new ScreenRectangle[]{
                    new ScreenRectangle(20, 10, 0, 8), new ScreenRectangle(720, 10, 152, 8),
                    ScreenRectangle.empty()}) {
                stack.clear();
                stack.push(clip);
                assertDoesNotThrow(() -> context.drawGradientString(font, "visible text", 0, 0,
                        GradientSpec.flat(-1), 1f));
            }
        } finally {
            var currentField = HudRenderContext.class.getDeclaredField("current");
            currentField.setAccessible(true);
            currentField.set(null, previous);
        }
    }

    @Test void zeroSizeAndOffscreenClipsRemainEmptyRatherThanBecomingUnclipped() {
        for (ScreenRectangle clip : new ScreenRectangle[]{
                new ScreenRectangle(20, 10, 0, 8), new ScreenRectangle(20, 10, 8, 0),
                new ScreenRectangle(720, 10, 152, 8), new ScreenRectangle(721, 10, 152, 8),
                new ScreenRectangle(-152, 10, 152, 8), new ScreenRectangle(20, 450, 152, 8),
                new ScreenRectangle(20, -8, 152, 8)}) {
            ScreenRectangle visible = HudTextScissor.visibleArea(clip, 720, 450);
            assertNotNull(visible, "An empty clip must never mean clipping is disabled");
            assertTrue(visible.width() <= 0 || visible.height() <= 0, clip.toString());
        }
    }

    @Test void partialClipsKeepOnlyTheVisiblePixelsOnEveryEdge() {
        assertEquals(new ScreenRectangle(700, 10, 20, 8),
                HudTextScissor.visibleArea(new ScreenRectangle(700, 10, 152, 8), 720, 450));
        assertEquals(new ScreenRectangle(0, 0, 100, 10),
                HudTextScissor.visibleArea(new ScreenRectangle(-52, -6, 152, 16), 720, 450));
        assertEquals(new ScreenRectangle(20, 445, 152, 5),
                HudTextScissor.visibleArea(new ScreenRectangle(20, 445, 152, 16), 720, 450));
    }

    @Test void resizeReclipsTheSameScreenSpaceRectangleAndPreservesOnePixel() {
        ScreenRectangle clip = new ScreenRectangle(719, 10, 152, 8);
        assertEquals(new ScreenRectangle(719, 10, 1, 8), HudTextScissor.visibleArea(clip, 720, 450));
        assertEquals(0, HudTextScissor.visibleArea(clip, 640, 450).width());
        assertEquals(clip, HudTextScissor.visibleArea(clip, 1000, 450));
        assertEquals(0, HudTextScissor.visibleArea(null, 0, 450).width());
    }

    @Test void nestedTransformedScissorsUseScreenCoordinatesBeforeViewportClipping() {
        Matrix3x2f pose = new Matrix3x2f().translate(680, 10).scale(2);
        ScreenRectangle parent = new ScreenRectangle(0, 0, 30, 10).transformAxisAligned(pose);
        ScreenRectangle child = new ScreenRectangle(10, 2, 30, 10).transformAxisAligned(pose);
        assertEquals(new ScreenRectangle(700, 14, 20, 16),
                HudTextScissor.visibleArea(parent.intersection(child), 720, 450));
        assertEquals(new ScreenRectangle(0, 0, 720, 450), HudTextScissor.visibleArea(null, 720, 450));
    }
}
