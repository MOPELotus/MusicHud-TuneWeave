package indi.mopelotus.musichud.companion.desktop;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Win32 window behavior only. No media, account or playback integration. */
final class WindowsWindow {
    private final MethodHandle releaseCapture;

    WindowsWindow(long hwnd) {
        var symbols = SymbolLookup.libraryLookup("user32", Arena.global());
        var linker = Linker.nativeLinker();
        releaseCapture = linker.downcallHandle(symbols.find("ReleaseCapture").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
        var get = linker.downcallHandle(symbols.find("GetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        var set = linker.downcallHandle(symbols.find("SetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
        try {
            MemorySegment window = MemorySegment.ofAddress(hwnd);
            long styles = (long) get.invokeExact(window, -20);
            long ignored = (long) set.invokeExact(window, -20, styles | 0x08000000L | 0x80L); // NOACTIVATE | TOOLWINDOW
        } catch (Throwable error) { throw new IllegalStateException("Could not configure Win32 HUD window", error); }
    }

    void releaseMouse() {
        try { int ignored = (int) releaseCapture.invokeExact(); }
        catch (Throwable error) { throw new IllegalStateException("Could not release Win32 mouse capture", error); }
    }
}
