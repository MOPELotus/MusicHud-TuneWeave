package indi.mopelotus.musichud.client.ui.hud;

/** Draft geometry in Minecraft GUI units; editing never mutates the saved config. */
public record HudEditorBounds(int x, int y, int width, int height) {
    public int offsetX(String horizontal, int screenWidth) {
        return switch (horizontal) {
            case "RIGHT" -> screenWidth - width - x;
            case "CENTER" -> x - (screenWidth - width) / 2;
            default -> x;
        };
    }

    public int offsetY(String vertical, int screenHeight) {
        return switch (vertical) {
            case "BOTTOM" -> screenHeight - height - y;
            case "CENTER" -> y - (screenHeight - height) / 2;
            default -> y;
        };
    }

    public static HudEditorBounds fromConfig(int x, int y, int width, int height,
                                             String horizontal, String vertical, int screenWidth, int screenHeight) {
        int left = switch (horizontal) {
            case "RIGHT" -> screenWidth - width - x;
            case "CENTER" -> (screenWidth - width) / 2 + x;
            default -> x;
        };
        int top = switch (vertical) {
            case "BOTTOM" -> screenHeight - height - y;
            case "CENTER" -> (screenHeight - height) / 2 + y;
            default -> y;
        };
        return new HudEditorBounds(left, top, width, height).clamp(screenWidth, screenHeight);
    }

    public HudEditorBounds clamp(int screenWidth, int screenHeight) {
        int sw = Math.max(16, screenWidth), sh = Math.max(16, screenHeight);
        int h = Math.clamp(height, 16, Math.min(256, sh));
        int w = Math.clamp(width, Math.min(h, sw), Math.min(800, sw));
        return new HudEditorBounds(Math.clamp(x, 0, sw - w), Math.clamp(y, 0, sh - h), w, h);
    }

    public HudEditorBounds drag(double dx, double dy, boolean resize, int sw, int sh) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return this;
        if (resize) return new HudEditorBounds(x, y,
                (int) Math.clamp(width + dx, 16, Math.max(16, sw - x)),
                (int) Math.clamp(height + dy, 16, Math.max(16, sh - y))).clamp(sw, sh);
        return new HudEditorBounds((int) Math.clamp(x + dx, 0, Math.max(0, sw - width)),
                (int) Math.clamp(y + dy, 0, Math.max(0, sh - height)), width, height).clamp(sw, sh);
    }

    public boolean contains(double px, double py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }
}
