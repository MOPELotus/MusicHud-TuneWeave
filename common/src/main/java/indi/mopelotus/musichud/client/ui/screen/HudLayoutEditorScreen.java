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
        if (minecraft.level == null) super.renderBackground(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        HudRendererManager.getInstance().renderEditorPreview(graphics);
        graphics.flush();
        super.render(graphics, mouseX, mouseY, delta);
    }
}
