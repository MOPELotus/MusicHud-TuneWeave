package indi.mopelotus.musichud.client.companion;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;

/** Logical HUD size plus transparent padding; never derived from the Minecraft window. */
public record DesktopCanvas(int guiWidth, int guiHeight, int scale) {
    public static DesktopCanvas of(int hudWidth, int hudHeight, int scale) {
        if (hudWidth <= 0 || hudHeight <= 0 || scale < 1 || scale > 4) {
            throw new IllegalArgumentException("Invalid desktop HUD canvas");
        }
        int width = Math.addExact(hudWidth, 4), height = Math.addExact(hudHeight, 4);
        CompanionWire.pixelBytes(Math.multiplyExact(width, scale), Math.multiplyExact(height, scale));
        return new DesktopCanvas(width, height, scale);
    }
    public int width() { return guiWidth * scale; }
    public int height() { return guiHeight * scale; }
}
