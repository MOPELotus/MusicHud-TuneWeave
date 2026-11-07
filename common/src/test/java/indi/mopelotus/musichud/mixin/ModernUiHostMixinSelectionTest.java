package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.compat.ModernUiHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModernUiHostMixinSelectionTest {
    @Test void lumenUsesItsOwnFixesWhileOfficialHostsRetainRequiredCompatibility() throws Exception {
        var selected = ModernUiHost.class.getDeclaredField("lumen");
        selected.setAccessible(true);
        Object previous = selected.get(null);
        try {
            var plugin = new MusicHudMixinPlugin();
            for (boolean lumen : new boolean[]{true, false}) {
                selected.set(null, lumen);
                for (String target : new String[]{"icyllis.modernui.mc.UIManager", "icyllis.modernui.text.SpanSet",
                        "icyllis.arc3d.opengl.GLTexture", "icyllis.modernui.graphics.Canvas"}) {
                    assertEquals(!lumen, plugin.shouldApplyMixin(target, "any.HostMixin"), target);
                    assertEquals(!lumen, plugin.shouldApplyMixin(target.replace('.', '/'), "any.HostMixin"), target);
                }
                assertTrue(plugin.shouldApplyMixin("net.minecraft.client.Minecraft", "any.ClientMixin"));
            }
        } finally {
            selected.set(null, previous);
        }
    }

}
