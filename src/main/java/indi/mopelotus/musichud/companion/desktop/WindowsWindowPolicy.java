package indi.mopelotus.musichud.companion.desktop;

/** Win32 overlay styles; preserve transparency, mouse passthrough and unrelated GLFW flags. */
final class WindowsWindowPolicy {
    private static final long APPWINDOW = 0x00040000L;
    private static final long TOOLWINDOW = 0x00000080L;
    private static final long NOACTIVATE = 0x08000000L;

    private WindowsWindowPolicy() {}

    static long overlayStyles(long styles) {
        return (styles & ~APPWINDOW) | TOOLWINDOW | NOACTIVATE;
    }

    static int topmostFlags(boolean frameChanged) {
        // NOSIZE | NOMOVE | NOACTIVATE; never NOZORDER, so HWND_TOPMOST takes effect.
        return 0x0001 | 0x0002 | 0x0010 | (frameChanged ? 0x0020 : 0);
    }
}
