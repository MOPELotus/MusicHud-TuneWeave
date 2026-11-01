package indi.mopelotus.musichud.companion.desktop;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Win32 window behavior only. No media, account or playback integration. */
final class WindowsWindow {
    private final MethodHandle releaseCapture;
    private final MethodHandle setWindowPos;
    private final MemorySegment window;

    WindowsWindow(long hwnd) {
        var symbols = SymbolLookup.libraryLookup("user32", Arena.global());
        var linker = Linker.nativeLinker();
        window = MemorySegment.ofAddress(hwnd);
        setWindowPos = linker.downcallHandle(symbols.find("SetWindowPos").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        releaseCapture = linker.downcallHandle(symbols.find("ReleaseCapture").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
        var get = linker.downcallHandle(symbols.find("GetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        var set = linker.downcallHandle(symbols.find("SetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
        try {
            long styles = (long) get.invokeExact(window, -20);
            long ignored = (long) set.invokeExact(window, -20, WindowsWindowPolicy.overlayStyles(styles));
            long applied = (long) get.invokeExact(window, -20);
            if (WindowsWindowPolicy.overlayStyles(applied) != applied) {
                throw new IllegalStateException("Win32 HUD taskbar styles were not applied");
            }
            positionTopmost(true);
        } catch (Throwable error) { throw new IllegalStateException("Could not configure Win32 HUD window", error); }
    }

    /** Reassert the floating position after a hidden HUD becomes visible, without activating it. */
    void ensureTopmost() { positionTopmost(false); }

    private void positionTopmost(boolean frameChanged) {
        try {
            int applied = (int) setWindowPos.invokeExact(window, MemorySegment.ofAddress(-1L),
                    0, 0, 0, 0, WindowsWindowPolicy.topmostFlags(frameChanged));
            if (applied == 0) throw new IllegalStateException("SetWindowPos(HWND_TOPMOST) failed");
        } catch (Throwable error) { throw new IllegalStateException("Could not keep Win32 HUD window topmost", error); }
    }

    void releaseMouse() {
        try { int ignored = (int) releaseCapture.invokeExact(); }
        catch (Throwable error) { throw new IllegalStateException("Could not release Win32 mouse capture", error); }
    }
}
