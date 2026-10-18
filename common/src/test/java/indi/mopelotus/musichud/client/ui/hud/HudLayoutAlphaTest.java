package indi.mopelotus.musichud.client.ui.hud;

import indi.mopelotus.musichud.client.ui.hud.metadata.Layout;
import indi.mopelotus.musichud.client.ui.hud.pipelines.HudUniformSnapshot;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class HudLayoutAlphaTest {
    @Test void childInheritsOpacityAndQueuedFramesRetainTheirOwnAlpha() {
        var base = new Layout("Base", 0, 0, 152, 52, 8);
        var image = new Layout("Album", 3, 3, 46, 46, 5);
        image.setParent(base);
        base.setAlpha(.5f);
        image.setAlpha(.8f);
        var fading = HudUniformSnapshot.capture(image);
        base.setAlpha(1f);
        var visible = HudUniformSnapshot.capture(image);
        assertEquals(.4f, alpha(fading), .00001f);
        assertEquals(.8f, alpha(visible), .00001f);
        assertNotEquals(fading, visible);
        assertEquals(80, fading.size());
    }

    @Test void invalidOpacityCannotEscapeIntoShaderUniforms() {
        var layout = new Layout("Base", 0, 0, 152, 52, 8);
        layout.setAlpha(Float.NaN);
        assertEquals(0f, layout.getEffectiveAlpha());
        layout.setAlpha(5);
        assertEquals(1f, layout.getEffectiveAlpha());
        layout.setAlpha(-2);
        assertEquals(0f, layout.getEffectiveAlpha());
    }

    private static float alpha(HudUniformSnapshot snapshot) {
        var bytes = ByteBuffer.allocate(snapshot.size()).order(ByteOrder.nativeOrder());
        snapshot.write(bytes);
        return bytes.getFloat(76);
    }
}
