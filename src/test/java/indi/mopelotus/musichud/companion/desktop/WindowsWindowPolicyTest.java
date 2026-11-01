package indi.mopelotus.musichud.companion.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsWindowPolicyTest {
    @Test void replacesGlfwTaskbarStyleWithoutLosingTransparencyOrClickThrough() {
        long appWindow = 0x00040000L;
        long layered = 0x00080000L, transparent = 0x20L, topmost = 0x08L;
        long unrelated = 0x100000000L;
        long styles = WindowsWindowPolicy.overlayStyles(appWindow | layered | transparent | topmost | unrelated);
        assertEquals(0, styles & appWindow);
        assertEquals(0x08000080L, styles & 0x08000080L);
        assertEquals(layered | transparent | topmost | unrelated,
                styles & (layered | transparent | topmost | unrelated));
        assertEquals(styles, WindowsWindowPolicy.overlayStyles(styles));
    }

    @Test void unlockedWindowRemainsAToolWindowWithoutAddingClickThrough() {
        long styles = WindowsWindowPolicy.overlayStyles(0);
        assertEquals(0, styles & 0x20L);
        assertEquals(0x08000080L, styles);
    }

    @Test void topmostRefreshNeverMovesResizesOrActivatesTheWindow() {
        for (boolean frameChanged : new boolean[]{false, true}) {
            int flags = WindowsWindowPolicy.topmostFlags(frameChanged);
            assertEquals(0x13, flags & 0x13); // NOSIZE | NOMOVE | NOACTIVATE
            assertEquals(0, flags & 0x04); // must allow the requested Z-order
            assertEquals(frameChanged ? 0x20 : 0, flags & 0x20);
        }
    }
}
