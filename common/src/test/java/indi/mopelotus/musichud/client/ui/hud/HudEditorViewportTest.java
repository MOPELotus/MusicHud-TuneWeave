package indi.mopelotus.musichud.client.ui.hud;

import icyllis.modernui.mc.MuiModApi;
import indi.mopelotus.musichud.client.ui.hud.metadata.HorizontalAlign;
import indi.mopelotus.musichud.client.ui.hud.metadata.VerticalAlign;
import indi.mopelotus.musichud.client.utils.ui.HudLayoutMath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class HudEditorViewportTest {
    @Test
    void scaleOnlyResizeRedrawsAgainstTargetPayloadBeforeMinecraftFieldsChange() {
        Harness h = new Harness();
        h.viewport.attach(new HudEditorViewport.Metrics(1920, 1080, 2));
        for (int scale : new int[]{3, 4}) {
            h.listener.onWindowResize(1920, 1080, scale, scale - 1);
            assertNotEquals(scale, h.viewport.metrics().guiScale());
            h.drain();
            var m = h.viewport.metrics();
            assertEquals(scale, m.guiScale());
            var r = HudLayoutMath.computeGuiRect(m.guiWidth(), m.guiHeight(), HorizontalAlign.LEFT,
                    VerticalAlign.TOP, 48, 40, 184, 66);
            assertEquals(48 * scale, r.left * m.guiScale());
            assertEquals(40 * scale, r.top * m.guiScale());
            assertEquals(232 * scale, r.right * m.guiScale());
            assertEquals(106 * scale, r.bottom * m.guiScale());
            // A physical pointer movement must use the same scale as the selection border.
            float pointerDelta = 18 * scale;
            assertEquals(18, pointerDelta / m.guiScale());
        }
        assertEquals(2, h.redraws);
        assertEquals(1, h.subscriptions);
    }

    @Test
    void rapidResizesDiscardOlderPayloadsEvenIfTheirUiCallbacksRunLast() {
        Harness h = new Harness();
        h.viewport.attach(new HudEditorViewport.Metrics(1920, 1080, 2));
        h.listener.onWindowResize(1920, 1080, 3, 2);
        h.listener.onWindowResize(1281, 801, 4, 3);
        h.pending.remove(1).run();
        h.drain();
        assertEquals(new HudEditorViewport.Metrics(1281, 801, 4), h.viewport.metrics());
        assertEquals(1, h.redraws);
    }

    @Test
    void detachUnsubscribesAndRejectsQueuedOrLateCallbacks() {
        Harness h = new Harness();
        h.viewport.attach(new HudEditorViewport.Metrics(1920, 1080, 2));
        var oldListener = h.listener;
        oldListener.onWindowResize(1920, 1080, 3, 2);
        h.viewport.detach();
        assertNull(h.listener);
        oldListener.onWindowResize(1920, 1080, 4, 3);
        h.drain();
        assertEquals(2, h.viewport.metrics().guiScale());
        assertEquals(0, h.redraws);
        assertEquals(1, h.unsubscriptions);
    }

    @Test
    void reattachmentCannotApplyPreviousAttachmentQueuedResize() {
        Harness h = new Harness();
        h.viewport.attach(new HudEditorViewport.Metrics(1920, 1080, 2));
        var oldListener = h.listener;
        oldListener.onWindowResize(1920, 1080, 3, 2);
        h.viewport.detach();
        h.viewport.attach(new HudEditorViewport.Metrics(960, 640, 2));
        oldListener.onWindowResize(1920, 1080, 4, 3);
        h.drain();
        assertEquals(new HudEditorViewport.Metrics(960, 640, 2), h.viewport.metrics());
        assertEquals(0, h.redraws);
        assertEquals(2, h.subscriptions);
    }

    @Test
    void rightAndBottomAnchorsUseMinecraftCeilingForNonDivisibleFramebuffer() {
        for (double scale : new double[]{2, 3, 4}) {
            var m = new HudEditorViewport.Metrics(1281, 803, scale);
            assertEquals((int) Math.ceil(1281 / scale), m.guiWidth());
            assertEquals((int) Math.ceil(803 / scale), m.guiHeight());
            var r = HudLayoutMath.computeGuiRect(m.guiWidth(), m.guiHeight(), HorizontalAlign.RIGHT,
                    VerticalAlign.BOTTOM, 17, 19, 184, 66);
            var values = HudLayoutMath.reverseGuiRect(m.guiWidth(), m.guiHeight(), HorizontalAlign.RIGHT,
                    VerticalAlign.BOTTOM, r.left, r.top, r.right, r.bottom);
            assertEquals(new HudLayoutMath.Values(17, 19, 184, 66), values);
            assertEquals((m.guiWidth() - 17) * scale, r.right * scale);
            assertEquals((m.guiHeight() - 19) * scale, r.bottom * scale);
        }
    }

    @Test
    void productionResizeAndPointerCancelShareTheSavingDragCompletionPath() throws Exception {
        ClassNode overlay = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "indi/mopelotus/musichud/client/ui/hud/HudEditOverlayView.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(overlay, 0);
        }
        assertTrue(calls(method(overlay, "onViewportChanged"), "finishDrag"));
        assertTrue(calls(method(overlay, "onTouchEvent"), "finishDrag"));
        assertTrue(calls(method(overlay, "onDetachedFromWindow"), "finishDrag"));
        assertTrue(calls(method(overlay, "finishDrag"), "save"));
        assertTrue(calls(method(overlay, "onAttachedToWindow"), "attach"));
        assertTrue(calls(method(overlay, "onDetachedFromWindow"), "detach"));
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static final class Harness {
        final List<Runnable> pending = new ArrayList<>();
        MuiModApi.OnWindowResizeListener listener;
        int subscriptions;
        int unsubscriptions;
        int redraws;
        final HudEditorViewport viewport = new HudEditorViewport(new HudEditorViewport.Metrics(1, 1, 1),
                pending::add, registered -> {
                    assertNull(listener);
                    listener = registered;
                    subscriptions++;
                }, registered -> {
                    assertSame(listener, registered);
                    listener = null;
                    unsubscriptions++;
                }, () -> redraws++);
        void drain() {
            while (!pending.isEmpty()) pending.removeFirst().run();
        }
    }
}
