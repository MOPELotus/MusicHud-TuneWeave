package indi.mopelotus.musichud.client.ui.hud.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransformingTest {
    @Test void terminalConsumerFailureRestoresTheOriginalPose() {
        PoseStack poses = new PoseStack();
        Matrix4f original = new Matrix4f(poses.last().pose());
        var transform = new HudRenderContext.Transforming(poses);
        assertThrows(IllegalStateException.class, () -> transform.translate(5, 9).end(scope -> {
            scope.scale(3); throw new IllegalStateException("draw failed");
        }));
        assertTrue(poses.clear());
        assertEquals(original, poses.last().pose());
    }
    @Test void nestedConsumerFailureRestoresParentBeforeItIsClosed() {
        PoseStack poses = new PoseStack();
        var transform = new HudRenderContext.Transforming(poses).translate(3, 7);
        Matrix4f parent = new Matrix4f(poses.last().pose());
        try {
            assertThrows(IllegalStateException.class, () -> transform.subTransform(scope -> {
                scope.scale(3).subTransform(nested -> { nested.translate(5, 4); throw new IllegalStateException("failed"); });
            }));
            assertFalse(poses.clear());
            assertEquals(parent, poses.last().pose());
        } finally { transform.end(); }
        assertTrue(poses.clear());
        assertEquals(new Matrix4f(), poses.last().pose());
    }
    @Test void errorsAlsoReleaseTheOwnedPose() {
        PoseStack poses = new PoseStack();
        assertThrows(AssertionError.class, () -> new HudRenderContext.Transforming(poses).end(scope -> {
            throw new AssertionError("renderer error");
        }));
        assertTrue(poses.clear());
    }
}
