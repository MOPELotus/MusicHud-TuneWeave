package indi.mopelotus.musichud.client.ui.screen;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.HudEditorBounds;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x66000000);
        int x = bounds.x(), y = bounds.y(), right = x + bounds.width(), bottom = y + bounds.height();
        HudRendererManager.getInstance().renderPreview(graphics, bounds);
        graphics.nextStratum();
        graphics.fill(x, y, right, y + 1, 0xff70c8ff);
        graphics.fill(x, bottom - 1, right, bottom, 0xff70c8ff);
        graphics.fill(x, y, x + 1, bottom, 0xff70c8ff);
        graphics.fill(right - 1, y, right, bottom, 0xff70c8ff);
        graphics.fill(right - 8, bottom - 8, right, bottom, 0xff70c8ff);
        graphics.drawString(font, "HUD " + bounds.width() + " × " + bounds.height(), x + 4, Math.max(18, y - 12), 0xffffffff);
        graphics.drawCenteredString(font, label("hint"), width / 2, 8, 0xffffffff);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || !bounds.contains(event.x(), event.y())) return false;
        dragStart = bounds; startX = event.x(); startY = event.y();
        resizing = event.x() >= bounds.x() + bounds.width() - 10 && event.y() >= bounds.y() + bounds.height() - 10;
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragStart == null) return super.mouseDragged(event, dx, dy);
        bounds = dragStart.drag(event.x() - startX, event.y() - startY, resizing, width, height);
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (dragStart == null) return super.mouseReleased(event);
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
