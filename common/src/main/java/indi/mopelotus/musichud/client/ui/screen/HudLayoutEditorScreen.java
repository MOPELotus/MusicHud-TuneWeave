package indi.mopelotus.musichud.client.ui.screen;

import icyllis.modernui.mc.UIManager;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Upstream HUD configuration fragment with this Minecraft version's ModernUI bridge. */
public final class HudLayoutEditorScreen extends MusicHudScreen {
    public HudLayoutEditorScreen(Screen previous) {
        super(UIManager.getInstance(), new HudConfigFragment(), null, previous, "HUD");
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) extractPanorama(graphics, delta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        HudRendererManager.getInstance().renderEditorPreview(graphics);
        graphics.nextStratum();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
