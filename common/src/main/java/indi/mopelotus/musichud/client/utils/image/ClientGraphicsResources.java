package indi.mopelotus.musichud.client.utils.image;

import com.mojang.blaze3d.systems.RenderSystem;
import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.graphics.drawable.RoundedImageDrawable;
import icyllis.modernui.resources.Resources;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.client.utils.OwnedResources;
import net.minecraft.client.Minecraft;

/** Only resources created by TuneWeave belong here; Minecraft's texture manager retains its own ownership. */
public final class ClientGraphicsResources {
    public static final OwnedResources RENDER = new OwnedResources(
            error -> MusicHud.LOGGER.warn("Failed to release a TuneWeave render resource", error));
    private static final OwnedResources UI = new OwnedResources(
            error -> MusicHud.LOGGER.warn("Failed to release a TuneWeave UI image", error));

    private ClientGraphicsResources() {}

    public static Image createImage(Bitmap bitmap) {
        if (UI.isStopped()) return null;
        if (Core.isOnUiThread()) return UI.create(() -> Image.createTextureFromBitmap(bitmap));
        if (Core.isOnRenderThread()) throw new IllegalStateException("UI images cannot be created on the render thread");
        // Volume-key notifications can originate on a worker. Queue before acquiring the resource lock.
        return UI.submit(task -> {
            if (!Core.getUiHandlerAsync().post(task)) {
                throw new java.util.concurrent.RejectedExecutionException("UI thread has stopped");
            }
        }, () -> UI.create(() -> Image.createTextureFromBitmap(bitmap))).join();
    }

    public static RoundedImageDrawable createRoundedDrawable(Resources resources, Image image) {
        return UI.create(() -> new OwnedRoundedDrawable(resources, image));
    }

    public static void releaseDrawable(Drawable drawable) {
        if (drawable instanceof OwnedRoundedDrawable owned) UI.release(owned);
    }

    // RoundedImageDrawable caches a shader that owns an additional native image reference.
    private static final class OwnedRoundedDrawable extends RoundedImageDrawable implements AutoCloseable {
        OwnedRoundedDrawable(Resources resources, Image image) {
            super(resources, image);
        }

        @Override public void close() {
            getPaint().setShader(null);
        }
    }

    public static void releaseImage(Image image) {
        UI.release(image);
    }

    public static void releaseTexture(AutoCloseable texture) {
        if (RenderSystem.isOnRenderThread()) {
            RENDER.release(texture);
        } else if (!RENDER.isStopped()) {
            // Retain ownership until the queued release runs. Shutdown may reach it first.
            Minecraft.getInstance().execute(() -> RENDER.release(texture));
        }
    }

    /** Minecraft.close HEAD, before its textures, renderer and GPU device are destroyed. */
    public static void beginShutdown() {
        UI.stop();
        RENDER.stop();
        HudRendererManager.shutdown();
        ImageUtils.cleanup();
        RENDER.close();
        MusicHud.LOGGER.info("Released TuneWeave render resources");
    }

    /** UIManager.run, after its event loop finishes and before its recording context is released. */
    public static void finishUiShutdown() {
        UI.close();
        ImageUtils.clearIconCache();
        PlatformIconUtils.clearCache();
        MusicHud.LOGGER.info("Released TuneWeave UI images");
    }
}
