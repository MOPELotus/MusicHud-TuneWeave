package indi.mopelotus.musichud.client.companion;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import indi.mopelotus.musichud.mixin.FrontendGpuDeviceCompanionAccessor;
import indi.mopelotus.musichud.mixin.GlDeviceCompanionAccessor;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import indi.mopelotus.musichud.companion.protocol.FramePublisher;
import indi.mopelotus.musichud.companion.protocol.FrameTimings;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;
import org.lwjgl.sdl.SDLVideo;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/** Original HUD renderer, with an independent desktop canvas and bounded asynchronous readback. */
public final class CompanionBridge {
    private static final DesktopDrawableBinding<GlDeviceCompanionAccessor> DRAWABLE = new DesktopDrawableBinding<>(new DesktopDrawableBinding.Access<>() {
        @Override public long current(GlDeviceCompanionAccessor backend) { return backend.musichud_tuneweave$currentWindow(); }
        @Override public long utility(GlDeviceCompanionAccessor backend) { return backend.musichud_tuneweave$utilityWindow(); }
        @Override public boolean isCurrent(GlDeviceCompanionAccessor backend, long window) {
            return RenderThreadGlState.window() == window
                    && RenderThreadGlState.context() == backend.musichud_tuneweave$context();
        }
        private void syncCache(GlDeviceCompanionAccessor accessor) {
            accessor.musichud_tuneweave$currentWindow(RenderThreadGlState.context() == accessor.musichud_tuneweave$context()
                    ? RenderThreadGlState.window() : 0);
        }
        @Override public void bind(GlDeviceCompanionAccessor backend, long window) {
            var accessor = backend;
            // Keep Ixeris' main-thread context release hook, but check SDL's result
            // before updating RenderPearl's cache (its makeCurrent ignores failures).
            boolean bound = SDLVideo.SDL_GL_MakeCurrent(window, accessor.musichud_tuneweave$context());
            String error = bound ? null : RenderThreadGlState.error();
            if (!bound || !isCurrent(backend, window)) {
                syncCache(accessor);
                throw new IllegalStateException("Cannot bind Companion drawable: SDL success=" + bound + ", expected window=" + window
                        + ", actual window=" + RenderThreadGlState.window() + ", expected context="
                        + accessor.musichud_tuneweave$context() + ", actual context=" + RenderThreadGlState.context()
                        + ", SDL error=" + error);
            }
            accessor.musichud_tuneweave$currentWindow(window);
        }
    });
    private static final GuiRenderState STATE = new GuiRenderState();
    private static volatile FramePublisher publisher;
    private static GuiRenderer renderer;
    private static TextureTarget target;
    private static final Slot[] SLOTS = {new Slot(), new Slot(), new Slot()};
    private static boolean drawingDesktop, replayed, stopped;
    private static boolean wasVisible;
    private static boolean preferencesRestored;
    private static long nextCaptureNanos;
    private static volatile int fps = 60;
    private static volatile int scale = 2;
    private static long drawn, dropped, readbacks, readbackNanos, reportStart;
    private static final FrameTimings READBACK_TIMINGS = new FrameTimings();

    private static final class Slot {
        GpuBuffer buffer;
        boolean busy;
    }

    private CompanionBridge() {}

    public static boolean enabled() { return publisher != null; }
    public static boolean connected() {
        var current = publisher;
        return current != null && current.connected();
    }
    public static boolean requiresFrameUpdates() { return connected() || DRAWABLE.needsRestore(); }
    public static boolean locked() {
        var current = publisher;
        return current == null || current.locked();
    }
    public static int scale() { return scale; }
    public static void setScale(int value) {
        scale = Math.clamp(value, 1, 4);
        ClientConfig.getInstance().setCompanionScale(scale);
    }
    public static RenderTarget destination(GuiRenderer owner, RenderTarget original) {
        return drawingDesktop && owner == renderer ? target : original;
    }

    public static int fps() { return fps; }
    public static void setFps(int value) {
        fps = value <= 30 ? 30 : 60;
        ClientConfig.getInstance().setCompanionFps(fps);
    }

    /** Called on the Minecraft thread by the preferences UI; saves the user's choice. */
    public static void setEnabled(boolean enabled) {
        if (stopped) return;
        ClientConfig.getInstance().setCompanionEnabled(enabled);
        setRuntimeEnabled(enabled);
    }

    /** Shutdown and rendering failures stop this session without erasing the saved preference. */
    private static void setRuntimeEnabled(boolean enabled) {
        if (stopped || enabled == enabled()) return;
        if (!enabled) {
            FramePublisher previous = publisher;
            publisher = null;
            if (previous != null) previous.close();
            CompanionDistribution.enabled(false);
            return;
        }
        try {
            publisher = new FramePublisher(CompanionWire.PORT);
            CompanionDistribution.enabled(true);
            MusicHud.LOGGER.info("Companion export listening on 127.0.0.1:{} (display only)", CompanionWire.PORT);
        } catch (IOException error) {
            MusicHud.LOGGER.warn("Cannot enable Companion export: {}", error.getMessage());
        }
    }

    public static void requestLocked(boolean locked) {
        var current = publisher;
        if (current == null) return;
        long generation = current.generation();
        Minecraft.getInstance().execute(() -> {
            if (publisher == current) current.setLocked(generation, locked);
        });
    }

    public static void setLocked(boolean locked) {
        var current = publisher;
        if (current != null) current.setLocked(locked);
    }

    public static void extract(GuiGraphicsExtractor normal, DeltaTracker delta) {
        HudRendererManager.getInstance().renderFrame(normal, delta);
    }

    public static boolean renderingWithoutGameSurface() {
        return desktopOnlyFrame;
    }

    private static GlDeviceCompanionAccessor glBackend() {
        var device = RenderSystem.tryGetDevice();
        if (device instanceof FrontendGpuDevice frontend
                && ((FrontendGpuDeviceCompanionAccessor) frontend).musichud_tuneweave$backend() instanceof GlDeviceCompanionAccessor backend) {
            return backend;
        }
        return null;
    }

    /** Bind before readback callbacks and preserve RenderPearl's context/window cache. */
    public static boolean prepareFrame() {
        // Keep surface acquisition, extraction and rendering on one path for this frame,
        // even if the IPC peer disconnects or the window is restored midway through it.
        desktopOnlyFrame = connected() && Minecraft.getInstance().getWindow().isIconified();
        try {
            DRAWABLE.update(glBackend(), renderingWithoutGameSurface());
            bindingFailureReported = false;
            return true;
        }
        catch (RuntimeException failure) {
            if (!bindingFailureReported) {
                MusicHud.LOGGER.warn("Cannot bind Companion drawable; disabling desktop export", failure);
                bindingFailureReported = true;
            }
            setRuntimeEnabled(false);
            // A failed restore must not run vanilla GPU work on an unknown context.
            // Keep the saved binding and retry next frame instead of crashing the game.
            desktopOnlyFrame = false;
            return DRAWABLE.tryRestore();
        }
    }

    private static boolean bindingFailureReported;
    private static boolean desktopOnlyFrame;

    /** Runs before queued texture uploads and ticks, even when a frame-skipping mod is active. */
    public static boolean prepareTick() {
        restorePreferencesOnce();
        if (bindingFailureReported && !DRAWABLE.tryRestore()) return false;
        bindingFailureReported = false;
        return true;
    }

    public static void restorePreferencesOnce() {
        if (stopped || preferencesRestored || !Minecraft.getInstance().isGameLoadFinished()) return;
        var config = ClientConfig.getInstance();
        fps = config.getCompanionFps();
        scale = config.getCompanionScale();
        preferencesRestored = true;
        setRuntimeEnabled(config.getCompanionEnabled());
    }

    /** Called after the game GUI, or instead of world drawing while minimized. */
    public static void render() {
        if (stopped) return;
        restorePreferencesOnce();
        if (!connected()) {
            boolean busy = false;
            for (Slot slot : SLOTS) busy |= slot.busy;
            if (!busy) releaseGraphics();
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isGameLoadFinished()) return;
        long now = System.nanoTime();
        if (now < nextCaptureNanos) return;
        nextCaptureNanos = Math.max(nextCaptureNanos + 1_000_000_000L / fps, now);
        boolean available = false;
        for (Slot slot : SLOTS) available |= !slot.busy;
        if (!available) { dropped++; return; }
        var config = ClientConfig.getInstance();
        // GuiRenderer uses WindowRenderState for projection, glyph sampling and scissors.
        // Scope the override to this renderer only; the real window and game settings stay intact.
        var window = minecraft.gameRenderer.gameRenderState().windowRenderState;
        int oldWidth = window.width, oldHeight = window.height, oldScale = window.guiScale;
        try {
            DesktopCanvas canvas = DesktopCanvas.of(config.getHudWidth(), config.getHudHeight(), scale);
            if (renderer == null) {
                renderer = new GuiRenderer(STATE, minecraft.gameRenderer.featureRenderDispatcher(), List.of());
                MusicHud.LOGGER.info("Companion capture device: {}", RenderSystem.getDevice().getDeviceInfo());
            }
            if (target == null || target.width != canvas.width() || target.height != canvas.height()) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget("TuneWeave Companion HUD", canvas.width(), canvas.height(),
                        GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            }
            window.width = canvas.width();
            window.height = canvas.height();
            window.guiScale = canvas.scale();
            STATE.reset();
            GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(minecraft, STATE, 0, 0) {
                @Override public int guiWidth() { return canvas.guiWidth(); }
                @Override public int guiHeight() { return canvas.guiHeight(); }
            };
            if (glBackend() != null) RenderThreadGlState.restoreFramebufferBindings();
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    target.getColorTexture(), new Vector4f(0f), target.getDepthTexture(), 0);
            drawingDesktop = true;
            replayed = false;
            HudRendererManager.getInstance().renderDesktopFrame(graphics, () -> {
                renderer.render();
                renderer.endFrame();
            });
            if (!replayed) clear();
        } catch (RuntimeException error) {
            MusicHud.LOGGER.warn("Companion rendering failed; disabling desktop export", error);
            setRuntimeEnabled(false);
            STATE.reset();
        } finally {
            drawingDesktop = false;
            window.width = oldWidth;
            window.height = oldHeight;
            window.guiScale = oldScale;
        }
    }

    /** Called only after an original draw range has completed, before its vertex buffer is retired. */
    public static void replay(GuiRenderer owner, Supplier<String> label, RenderTarget main,
                              GpuBufferSlice transforms, int start, int end) {
        FramePublisher current = publisher;
        if (owner != renderer || !drawingDesktop || current == null || !current.connected()) return;
        long now = System.nanoTime();
        try {
            Slot slot = null;
            for (Slot candidate : SLOTS) if (!candidate.busy) { slot = candidate; break; }
            if (slot == null) { dropped++; replayed = true; return; }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            int width = target.width, height = target.height;
            int bytes = CompanionWire.pixelBytes(width, height);
            if (slot.buffer == null || slot.buffer.size() != bytes) {
                if (slot.buffer != null) slot.buffer.close();
                slot.buffer = RenderSystem.getDevice().createBuffer(() -> "Companion RGBA readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            }
            FramePublisher.Capture capture = current.capture();
            if (capture == null) return;
            Slot reserved = slot;
            reserved.busy = true;
            wasVisible = replayed = true;
            drawn++;
            long submitted = System.nanoTime();
            try {
                encoder.copyTextureToBuffer(target.getColorTexture(), reserved.buffer, 0, () -> {
                    try {
                        if (stopped || publisher != current) return;
                        byte[] pixels = new byte[bytes];
                        try (var mapped = reserved.buffer.map(true, false)) {
                            // RenderPearl's screenshot path uses bottom-up rows on both backends.
                            int stride = width * 4;
                            for (int row = 0; row < height; row++) {
                                mapped.data().get((height - row - 1) * stride, pixels, row * stride, stride);
                            }
                        }
                        current.publish(capture, width, height, pixels);
                        readbacks++;
                        readbackNanos += System.nanoTime() - submitted;
                        READBACK_TIMINGS.add((System.nanoTime() - submitted) / 1_000_000.0);
                    } catch (RuntimeException error) {
                        MusicHud.LOGGER.warn("Companion readback failed; disabling desktop export", error);
                        setRuntimeEnabled(false);
                    } finally { reserved.busy = false; }
                }, 0, 0, 0, width, height);
            } catch (RuntimeException error) { reserved.busy = false; throw error; }
            if (reportStart == 0) reportStart = now;
            if (now - reportStart >= 10_000_000_000L) {
                MusicHud.LOGGER.info("Companion: captures={}, busyDrops={}, readbacks={}, readback ms mean={} p50={} p95={}",
                        drawn, dropped, readbacks, readbacks == 0 ? 0 : readbackNanos / (readbacks * 1_000_000.0),
                        READBACK_TIMINGS.percentile(.5), READBACK_TIMINGS.percentile(.95));
                READBACK_TIMINGS.reset();
                drawn = dropped = readbacks = readbackNanos = 0;
                reportStart = now;
            }
        } catch (RuntimeException error) {
            MusicHud.LOGGER.warn("Companion capture failed; disabling desktop export", error);
            setRuntimeEnabled(false);
        }
    }

    private static void clear() {
        if (!wasVisible) return;
        wasVisible = false;
        var current = publisher;
        if (current != null) current.clear();
    }

    public static boolean shutdown() {
        setRuntimeEnabled(false);
        stopped = true;
        CompanionDistribution.shutdown();
        STATE.reset();
        if (!DRAWABLE.tryRestore()) {
            MusicHud.LOGGER.warn("Cannot restore game drawable during shutdown; leaving GPU resources to process cleanup");
            return false;
        }
        releaseGraphics();
        return true;
    }

    private static void releaseGraphics() {
        if (renderer != null) { renderer.close(); renderer = null; }
        if (target != null) { target.destroyBuffers(); target = null; }
        for (Slot slot : SLOTS) {
            if (slot.buffer != null) { slot.buffer.close(); slot.buffer = null; }
        }
    }
}
