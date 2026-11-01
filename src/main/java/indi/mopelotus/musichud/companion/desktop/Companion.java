package indi.mopelotus.musichud.companion.desktop;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import indi.mopelotus.musichud.companion.protocol.FrameTimings;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Locale;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Pixel display only: the game owns all HUD content and animation. */
public final class Companion {
    private long window;
    private boolean locked = true;
    private boolean dragging;
    private int dragX, dragY, originX, originY;
    private double cursorX, cursorY;
    private int width = 1, height = 1;
    private WindowsWindow windows;
    private final WindowPosition position;
    private final int port;
    private final OwnerProcess owner;
    private final Path stopFile;
    private final Integer requestedX, requestedY;
    private ByteBuffer upload;
    private volatile boolean exitRequested;

    private Companion(int port, Integer x, Integer y, Path positionPath, OwnerProcess owner, Path stopFile) {
        this.port = port;
        this.owner = owner;
        this.stopFile = stopFile;
        requestedX = x;
        requestedY = y;
        position = new WindowPosition(positionPath);
    }

    public static void main(String[] args) {
        int port = CompanionWire.PORT;
        Integer x = null, y = null;
        Long ownerPid = null, ownerStart = null;
        Path stopFile = null;
        String dataHome = System.getenv("LOCALAPPDATA");
        Path data = dataHome == null ? Path.of(System.getProperty("user.home"), ".config") : Path.of(dataHome);
        Path positionPath = data.resolve("MusicHud-TuneWeave/Companion/position.properties");
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--stop-file" -> stopFile = stopFile(argumentValue(args, ++i));
                    case "--owner-pid" -> ownerPid = Long.parseLong(argumentValue(args, ++i));
                    case "--owner-start" -> ownerStart = Long.parseLong(argumentValue(args, ++i));
                    case "--version" -> { System.out.println(version()); return; }
                    case "--port" -> port = Integer.parseInt(argumentValue(args, ++i));
                    case "--x" -> x = Integer.parseInt(argumentValue(args, ++i));
                    case "--y" -> y = Integer.parseInt(argumentValue(args, ++i));
                    case "--position-file" -> positionPath = Path.of(argumentValue(args, ++i));
                    case "--help" -> {
                        System.out.println("Companion [--port 39841] [--x pixels --y pixels] [--position-file path] [--owner-pid PID --owner-start epochMillis] [--stop-file absolutePath] [--version]");
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (port < 1 || port > 65535 || (x == null) != (y == null)) throw new IllegalArgumentException("Invalid port or position");
            OwnerProcess owner = OwnerProcess.of(ownerPid, ownerStart);
            if ((owner != null && !owner.alive()) || stopRequested(stopFile)) return;
            try (var session = new DesktopSession(data.resolve("MusicHud-TuneWeave/Companion"), port)) {
                try {
                    System.out.println("Companion version=" + version() + ", owner=" + owner);
                    new Companion(port, x, y, positionPath, owner, stopFile).run();
                }
                catch (RuntimeException error) { error.printStackTrace(System.err); throw error; }
            } catch (java.io.IOException error) { throw new IllegalStateException(error.getMessage(), error); }
        } catch (RuntimeException error) {
            System.err.println("Companion: " + error.getMessage());
            throw error;
        }
    }

    static Path stopFile(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("--stop-file must be an absolute path");
        return path;
    }

    static boolean stopRequested(Path path) { return path != null && java.nio.file.Files.exists(path); }

    static String version() {
        try (var stream = Companion.class.getResourceAsStream("/companion-version.txt")) {
            if (stream == null) throw new IllegalStateException("Missing Companion version resource");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot read Companion version", error); }
    }

    private static String argumentValue(String[] args, int index) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException("Missing value for " + args[index - 1]);
        }
        return args[index];
    }

    private void run() {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE);
            glfwWindowHint(GLFW_FLOATING, GLFW_TRUE);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
            glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
            glfwWindowHint(GLFW_MOUSE_PASSTHROUGH, GLFW_TRUE);
            glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_FALSE);
            glfwWindowHint(GLFW_ALPHA_BITS, 8);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
            window = glfwCreateWindow(1, 1, "MusicHud Companion", NULL, NULL);
            if (window == NULL) throw new IllegalStateException("Could not create transparent HUD window");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String renderer = glGetString(GL_RENDERER);
            System.out.println("GPU vendor=" + glGetString(GL_VENDOR) + ", renderer=" + renderer + ", version=" + glGetString(GL_VERSION));
            String device = String.valueOf(renderer).toLowerCase(Locale.ROOT);
            if (device.contains("llvmpipe") || device.contains("lavapipe") || device.contains("swiftshader")
                    || device.contains("software") || device.contains("microsoft basic")) {
                throw new IllegalStateException("A physical GPU is required for Companion verification");
            }
            if (glfwGetWindowAttrib(window, GLFW_TRANSPARENT_FRAMEBUFFER) != GLFW_TRUE) {
                throw new IllegalStateException("Transparent framebuffer unavailable; check desktop compositing");
            }
            if (glfwGetPlatform() == GLFW_PLATFORM_WIN32) windows = new WindowsWindow(GLFWNativeWin32.glfwGetWin32Window(window));
            int[] saved = requestedX == null ? position.load() : new int[]{requestedX, requestedY};
            if (saved != null) glfwSetWindowPos(window, saved[0], saved[1]);
            else moveToPrimary();
            double[] initialX = new double[1], initialY = new double[1];
            glfwGetCursorPos(window, initialX, initialY);
            cursorX = initialX[0]; cursorY = initialY[0];
            glfwSetCursorPosCallback(window, (handle, x, y) -> {
                cursorX = x; cursorY = y;
                if (dragging && !locked) {
                    int[] wx = new int[1], wy = new int[1];
                    glfwGetWindowPos(window, wx, wy);
                    glfwSetWindowPos(window, originX + wx[0] + (int) x - dragX, originY + wy[0] + (int) y - dragY);
                }
            });
            glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
                if (button != GLFW_MOUSE_BUTTON_LEFT || locked) return;
                if (action == GLFW_PRESS) {
                    int[] wx = new int[1], wy = new int[1];
                    glfwGetWindowPos(window, wx, wy);
                    originX = wx[0]; originY = wy[0];
                    dragX = originX + (int) cursorX; dragY = originY + (int) cursorY;
                    dragging = true;
                } else if (action == GLFW_RELEASE) finishDrag();
            });
            glfwSetMonitorCallback((monitor, event) -> ensureOnScreen());
            glfwSwapInterval(1);
            int texture = glGenTextures();
            try (FrameReceiver receiver = new FrameReceiver(port);
                 ExitTray tray = new ExitTray(() -> { exitRequested = true; glfwPostEmptyEvent(); })) {
                loop(receiver, texture);
            } finally { glDeleteTextures(texture); }
        } finally {
            if (upload != null) MemoryUtil.memFree(upload);
            if (window != NULL) {
                finishDrag();
                if (windows != null) windows.releaseMouse();
                var mouse = glfwSetMouseButtonCallback(window, null);
                if (mouse != null) mouse.free();
                var cursor = glfwSetCursorPosCallback(window, null);
                if (cursor != null) cursor.free();
                glfwDestroyWindow(window);
            }
            var monitor = glfwSetMonitorCallback(null);
            if (monitor != null) monitor.free();
            glfwTerminate();
            glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private void loop(FrameReceiver receiver, int texture) {
        CompanionWire.Message displayed = null;
        boolean visible = false;
        long reportStart = System.nanoTime(), presentations = 0, totalLatencyMillis = 0;
        FrameTimings latencies = new FrameTimings();
        while (!exitRequested && !glfwWindowShouldClose(window) && (owner == null || owner.alive()) && !stopRequested(stopFile)) {
            long started = System.nanoTime();
            glfwPollEvents();
            DisplayState state = receiver.state.get();
            boolean expired = state.expired(started);
            boolean nextLocked = expired || state.locked();
            if (nextLocked != locked) {
                finishDrag();
                if (windows != null) windows.releaseMouse();
                locked = nextLocked;
                glfwSetWindowAttrib(window, GLFW_MOUSE_PASSTHROUGH, locked ? GLFW_TRUE : GLFW_FALSE);
                if (!locked) {
                    double[] x = new double[1], y = new double[1];
                    glfwGetCursorPos(window, x, y);
                    cursorX = x[0]; cursorY = y[0];
                }
                System.out.println("HUD " + (locked ? "locked (mouse passthrough)" : "unlocked (drag to move)"));
            }
            CompanionWire.Message frame = expired ? null : state.frame();
            boolean uploaded = false;
            if (frame != null && frame != displayed) {
                boolean resized = displayed == null || width != frame.width() || height != frame.height();
                width = frame.width(); height = frame.height();
                if (resized) {
                    glfwSetWindowSize(window, width, height);
                    ensureOnScreen();
                }
                if (upload == null || upload.capacity() < frame.rgba().length) {
                    if (upload != null) MemoryUtil.memFree(upload);
                    upload = MemoryUtil.memAlloc(frame.rgba().length);
                }
                upload.clear().put(frame.rgba()).flip();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                if (resized) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);
                else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, upload);
                displayed = frame;
                uploaded = true;
            }
            if (frame != null && !visible) { glfwShowWindow(window); visible = true; }
            if (frame == null && visible) {
                finishDrag();
                if (windows != null) windows.releaseMouse();
                glfwHideWindow(window); visible = false; displayed = null;
            }
            if (visible) {
                int[] fw = new int[1], fh = new int[1];
                glfwGetFramebufferSize(window, fw, fh);
                glViewport(0, 0, fw[0], fh[0]);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
                glDisable(GL_BLEND);
                glEnable(GL_TEXTURE_2D);
                glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
                glMatrixMode(GL_MODELVIEW); glLoadIdentity();
                glBindTexture(GL_TEXTURE_2D, texture);
                glColor4f(1, 1, 1, 1);
                glBegin(GL_QUADS);
                glTexCoord2f(0, 0); glVertex2f(0, 0);
                glTexCoord2f(1, 0); glVertex2f(width, 0);
                glTexCoord2f(1, 1); glVertex2f(width, height);
                glTexCoord2f(0, 1); glVertex2f(0, height);
                glEnd();
                glfwSwapBuffers(window);
                if (uploaded) {
                    // Include the draw and swap call; this still does not measure display scanout.
                    long latency = Math.max(0, System.currentTimeMillis() - frame.capturedAtMillis());
                    totalLatencyMillis += latency;
                    latencies.add(latency);
                    presentations++;
                }
            }
            if (started - reportStart >= 10_000_000_000L) {
                System.out.printf(Locale.ROOT, "presented FPS=%.1f, capture-to-swap-return ms: mean=%.1f p50=%.1f p95=%.1f%n",
                        presentations * 1e9 / (started - reportStart), presentations == 0 ? 0 : (double) totalLatencyMillis / presentations,
                        latencies.percentile(.5), latencies.percentile(.95));
                latencies.reset();
                presentations = totalLatencyMillis = 0;
                reportStart = started;
            }
            long remaining = 16_666_667L - (System.nanoTime() - started);
            if (remaining > 0) java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
        finishDrag();
    }

    private void finishDrag() {
        if (!dragging) return;
        dragging = false;
        int[] x = new int[1], y = new int[1];
        glfwGetWindowPos(window, x, y);
        position.save(x[0], y[0]);
    }

    private void moveToPrimary() {
        int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
        glfwGetMonitorWorkarea(glfwGetPrimaryMonitor(), x, y, w, h);
        glfwSetWindowPos(window, x[0] + 24, y[0] + 24);
    }

    private void ensureOnScreen() {
        int[] x = new int[1], y = new int[1], mx = new int[1], my = new int[1], mw = new int[1], mh = new int[1];
        glfwGetWindowPos(window, x, y);
        var monitors = glfwGetMonitors();
        if (monitors == null) return;
        for (int i = 0; i < monitors.limit(); i++) {
            glfwGetMonitorWorkarea(monitors.get(i), mx, my, mw, mh);
            if (WindowPosition.intersects(x[0], y[0], width, height, mx[0], my[0], mw[0], mh[0])) return;
        }
        moveToPrimary();
    }
}
