package indi.mopelotus.musichud.client.utils.ui;

import icyllis.modernui.graphics.RectF;
import indi.mopelotus.musichud.client.ui.hud.metadata.HorizontalAlign;
import indi.mopelotus.musichud.client.ui.hud.metadata.VerticalAlign;

public final class HudLayoutMath {
    private HudLayoutMath() {
    }

    public static RectF computeGuiRect(int guiWidth, int guiHeight,
                                       HorizontalAlign horizontalAlign, VerticalAlign verticalAlign,
                                       int offsetX, int offsetY, int width, int height) {
        float x = switch (horizontalAlign) {
            case LEFT -> offsetX;
            case CENTER -> guiWidth / 2f + offsetX - width / 2f;
            case RIGHT -> guiWidth - width - offsetX;
        };
        float y = switch (verticalAlign) {
            case TOP -> offsetY;
            case CENTER -> guiHeight / 2f + offsetY - height / 2f;
            case BOTTOM -> guiHeight - height - offsetY;
        };
        return new RectF(x, y, x + width, y + height);
    }

    public static Values reverseGuiRect(int guiWidth, int guiHeight,
                                        HorizontalAlign horizontalAlign, VerticalAlign verticalAlign,
                                        float left, float top, float right, float bottom) {
        int width = Math.round(right - left);
        int height = Math.round(bottom - top);
        int offsetX = switch (horizontalAlign) {
            case LEFT -> Math.round(left);
            case CENTER -> Math.round(left - guiWidth / 2f + width / 2f);
            case RIGHT -> Math.round(guiWidth - right);
        };
        int offsetY = switch (verticalAlign) {
            case TOP -> Math.round(top);
            case CENTER -> Math.round(top - guiHeight / 2f + height / 2f);
            case BOTTOM -> Math.round(guiHeight - bottom);
        };
        return new Values(offsetX, offsetY, width, height);
    }

    public record Values(int offsetX, int offsetY, int width, int height) {
    }
}
