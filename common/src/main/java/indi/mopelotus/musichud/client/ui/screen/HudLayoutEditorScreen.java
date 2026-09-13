package indi.mopelotus.musichud.client.ui.screen;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.HudEditorBounds;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Independent screen with a disposable layout draft and an explicit save action. */
public final class HudLayoutEditorScreen extends Screen {
    private final Screen previous;
    private final ClientConfig config = ClientConfig.getInstance();
    private HudEditorBounds bounds;
    private HudEditorBounds dragStart;
    private double startX, startY;
    private boolean resizing;

    public HudLayoutEditorScreen(Screen previous) {
        super(label("title"));
        this.previous = previous;
    }

    private static Component label(String key) {
        return Component.translatable(MusicHud.MOD_ID + ".hudEditor." + key);
    }

    @Override protected void init() {
        bounds = bounds == null ? HudEditorBounds.fromConfig(config.getHudOffsetX(), config.getHudOffsetY(),
                config.getHudWidth(), config.getHudHeight(), config.getHudHorizontalPosition(),
                config.getHudVerticalPosition(), width, height) : bounds.clamp(width, height);
        dragStart = null;
        addRenderableWidget(Button.builder(label("save"), button -> save()).bounds(width / 2 - 105, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(label("cancel"), button -> onClose()).bounds(width / 2 + 5, height - 26, 100, 20).build());
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0x66000000);
        int x = bounds.x(), y = bounds.y(), right = x + bounds.width(), bottom = y + bounds.height();
        HudRendererManager.getInstance().renderPreview(graphics, bounds);
        graphics.flush();
        graphics.fill(x, y, right, y + 1, 0xff70c8ff);
        graphics.fill(x, bottom - 1, right, bottom, 0xff70c8ff);
        graphics.fill(x, y, x + 1, bottom, 0xff70c8ff);
        graphics.fill(right - 1, y, right, bottom, 0xff70c8ff);
        graphics.fill(right - 8, bottom - 8, right, bottom, 0xff70c8ff);
        graphics.drawString(font, "HUD " + bounds.width() + " × " + bounds.height(), x + 4, Math.max(18, y - 12), 0xffffffff);
        graphics.drawCenteredString(font, label("hint"), width / 2, 8, 0xffffffff);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || !bounds.contains(mouseX, mouseY)) return false;
        dragStart = bounds; startX = mouseX; startY = mouseY;
        resizing = mouseX >= bounds.x() + bounds.width() - 10 && mouseY >= bounds.y() + bounds.height() - 10;
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragStart == null) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        bounds = dragStart.drag(mouseX - startX, mouseY - startY, resizing, width, height);
        return true;
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragStart == null) return super.mouseReleased(mouseX, mouseY, button);
        dragStart = null; return true;
    }

    private void save() {
        config.setHudOffsetX(bounds.offsetX(config.getHudHorizontalPosition(), width));
        config.setHudOffsetY(bounds.offsetY(config.getHudVerticalPosition(), height));
        config.setHudWidth(bounds.width()); config.setHudHeight(bounds.height());
        config.setHudCornerRadius(Math.min(config.getHudCornerRadius(), bounds.height() / 2));
        config.save();
        HudRendererManager.getInstance().updateLayoutFromConfig();
        HudRendererManager.getInstance().refreshStyle();
        onClose();
    }

    @Override public void onClose() { minecraft.setScreen(previous); }
    @Override public boolean isPauseScreen() { return false; }
}
