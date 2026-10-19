package indi.mopelotus.musichud.client.ui.hud.renderer;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jetbrains.annotations.Nullable;

/** Screen-space clipping for HUD glyphs submitted directly to the GUI render state. */
final class HudTextScissor {
    private HudTextScissor() {}

    static ScreenRectangle visibleArea(@Nullable ScreenRectangle scissor, int guiWidth, int guiHeight) {
        if (guiWidth <= 0 || guiHeight <= 0) return ScreenRectangle.empty();
        ScreenRectangle viewport = new ScreenRectangle(0, 0, guiWidth, guiHeight);
        if (scissor == null) return viewport;
        ScreenRectangle visible = viewport.intersection(scissor);
        return visible == null ? ScreenRectangle.empty() : visible;
    }
}
