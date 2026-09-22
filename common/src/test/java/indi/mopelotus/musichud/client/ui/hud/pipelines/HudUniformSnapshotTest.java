package indi.mopelotus.musichud.client.ui.hud.pipelines;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class HudUniformSnapshotTest {
    @Test void queuedFramesKeepTheirOwnGeometryWhenTheEditorResizesAgain() {
        var layout = new MutableGeometry();
        var first = HudUniformSnapshot.capture(layout);
        layout.width = 240;
        layout.height = 100;
        var resized = HudUniformSnapshot.capture(layout);
        layout.width = 800;
        layout.height = 80;
        assertGeometry(first, 152, 52);
        assertGeometry(resized, 240, 100);
        assertNotEquals(first, resized);
        layout.width = 152;
        layout.height = 52;
        assertEquals(first, HudUniformSnapshot.capture(layout));
    }

    @Test void uniformPaddingAndRepeatedWritesAreStable() {
        var snapshot = HudUniformSnapshot.capture(new MutableGeometry());
        var buffer = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        buffer.position(16);
        snapshot.write(buffer);
        snapshot.write(buffer);
        assertEquals(48, buffer.position());
        assertEquals(0, buffer.getInt(28));
        assertEquals(buffer.getFloat(16), buffer.getFloat(32));
    }

    @Test void realMatrixSerializationUsesNativeMemoryAndCopiesItBeforeTheStackCloses() {
        var matrix = new org.joml.Matrix4f().translation(17, 29, 0);
        HudUniform geometry = new HudUniform() {
            public String getUBOName() { return "MHPosition"; }
            public int getUBOSize() { return 80; }
            public void write(ByteBuffer buffer) {
                assertTrue(buffer.isDirect(), "JOML's runtime writer requires a native buffer");
                HudUniform.super.write(buffer);
            }
            public void write(Std140Builder builder) { builder.putMat4f(matrix).putVec3(120, 40, 8); }
            public boolean shouldUseBuffer(HudUniform previous) { return false; }
        };
        var snapshot = HudUniformSnapshot.capture(geometry);
        matrix.translation(999, 999, 0);
        var copy = ByteBuffer.allocate(snapshot.size()).order(ByteOrder.nativeOrder());
        snapshot.write(copy);
        assertEquals(17, copy.getFloat(48));
        assertEquals(29, copy.getFloat(52));
        assertEquals(120, copy.getFloat(64));
        assertEquals(0, copy.getInt(76));
    }

    private static void assertGeometry(HudUniformSnapshot snapshot, float width, float height) {
        var bytes = ByteBuffer.allocate(snapshot.size()).order(ByteOrder.nativeOrder());
        snapshot.write(bytes);
        assertEquals(width, bytes.getFloat(0));
        assertEquals(height, bytes.getFloat(4));
    }

    private static class MutableGeometry implements HudUniform {
        float width = 152, height = 52;
        public String getUBOName() { return "MHTestPosition"; }
        public int getUBOSize() { return 16; }
        public void write(Std140Builder builder) { builder.putVec3(width, height, 8); }
        public boolean shouldUseBuffer(HudUniform previous) { return previous == this; }
    }
}
