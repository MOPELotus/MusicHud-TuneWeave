package indi.mopelotus.musichud.client.ui.screen;

import icyllis.modernui.mc.UIManager;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** Upstream HUD configuration fragment with this Minecraft version's ModernUI bridge. */
public final class HudLayoutEditorScreen extends MusicHudScreen {
    public HudLayoutEditorScreen(Screen previous) {
        super(UIManager.getInstance(), new HudConfigFragment(), null, previous, "HUD");
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) renderPanorama(graphics, delta);
        HudRendererManager.getInstance().renderEditorPreview(graphics);
        graphics.flush();
        // ModernUI 1.21.1 consumes its frame in this phase, after the native preview.
        UIManager.getInstance().render(graphics, mouseX, mouseY, delta);
    }
}
