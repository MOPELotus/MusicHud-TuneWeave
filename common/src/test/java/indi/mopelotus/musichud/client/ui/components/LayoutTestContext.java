package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.resources.ResourceId;
import icyllis.modernui.resources.Resources;

/** Runs the real measure/layout code without creating a rendering device. */
final class LayoutTestContext extends Context {
    static {
        // Construction initializes layout policy only; run() would create a graphics device.
        if (icyllis.modernui.ModernUI.getInstance() == null) new icyllis.modernui.ModernUI();
    }
    private final Resources resources = new Resources();
    private final Resources.Theme theme = resources.newTheme();

    @Override public Resources getResources() { return resources; }
    @Override public Resources.Theme getTheme() { return theme; }
    @Override public void setTheme(ResourceId style) { theme.applyStyle(style, true); }
    @Override public Object getSystemService(String name) { return null; }
}
