package indi.mopelotus.musichud.client.ui.components;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.drawable.ImageDrawable;
import icyllis.modernui.view.ViewTreeObserver;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.ImageView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.function.Supplier;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;

public class PlayerHeadView extends FrameLayout {
    private static final int HEAD_SIZE = 8;
    private static final int SKIN_FACE_U = 8;
    private static final int SKIN_FACE_V = 8;
    private static final int SKIN_HAT_U = 40;
    private static final int SKIN_HAT_V = 8;
    private static final float FACE_SCALE = 0.87f;
    private static final Logger logger = MusicHud.getLogger(PlayerHeadView.class);
    private final ImageView faceView;
    private final ImageView hatView;
    @Getter
    private Supplier<ResourceLocation> playerSkinSupplier;
    @Setter
    @Getter
    private ResourceLocation skin;
    private ResourceLocation lastRenderedSkin;
    private Image faceImage;
    private Image hatImage;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
//        if (RenderSystem.isOnRenderThread()) {
        updateHeadImage();
//        } else {
//            Minecraft.getInstance().submit(this::updateHeadImage);
//        }
        return true;
    };

    public PlayerHeadView(Context context) {
        super(context);
        faceView = new ImageView(context);
        faceView.setScaleType(ImageView.ScaleType.FIT_XY);
        hatView = new ImageView(context);
        hatView.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(faceView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
        addView(hatView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int w = right - left;
        int h = bottom - top;
        if (w > 0 && h > 0) {
            faceView.setPivotX(w / 2f);
            faceView.setPivotY(h / 2f);
            faceView.setScaleX(FACE_SCALE);
            faceView.setScaleY(FACE_SCALE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastRenderedSkin = null;
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        clearImages();
        lastRenderedSkin = null;
    }

    public void setPlayerSkinSupplier(@Nullable Supplier<ResourceLocation> playerSkinSupplier) {
        this.playerSkinSupplier = playerSkinSupplier;
        skin = playerSkinSupplier == null ? null : playerSkinSupplier.get();
//        if (RenderSystem.isOnRenderThread()) {
        updateHeadImage();
//        } else {
//            Minecraft.getInstance().submit(this::updateHeadImage);
//        }
    }

    private void updateHeadImage() {
        if (ClientGraphicsResources.RENDER.isStopped()) return;
        if (playerSkinSupplier != null) {
            skin = playerSkinSupplier.get();
        }
        if (skin == null) {
            clearImages();
            lastRenderedSkin = null;
            return;
        }
        if (skin.equals(lastRenderedSkin)) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        NativeImage skinImage = null;
        boolean readFromStream = false;
        try {
            SkinImageResult skinImageResult;
            if (RenderSystem.isOnRenderThread()) {
                skinImageResult = loadSkinImage();
            } else {
                skinImageResult = ClientGraphicsResources.RENDER.submit(minecraft::execute, this::loadSkinImage).get();
            }
            skinImage = skinImageResult.skinImage;
            readFromStream = skinImageResult.readFromStream;
            if (skinImage == null) return;

            try (NativeImage faceNat = new NativeImage(NativeImage.Format.RGBA, HEAD_SIZE, HEAD_SIZE, false);
                 NativeImage hatNat = new NativeImage(NativeImage.Format.RGBA, HEAD_SIZE, HEAD_SIZE, false)) {
                skinImage.copyRect(faceNat, SKIN_FACE_U, SKIN_FACE_V, 0, 0, HEAD_SIZE, HEAD_SIZE, false, false);
                skinImage.copyRect(hatNat, SKIN_HAT_U, SKIN_HAT_V, 0, 0, HEAD_SIZE, HEAD_SIZE, false, false);

                Image nextFace = null;
                Image nextHat = null;
                try (Bitmap faceBitmap = ImageUtils.convertNativeImageToBitmap(faceNat);
                     Bitmap hatBitmap = ImageUtils.convertNativeImageToBitmap(hatNat)) {
                    nextFace = ClientGraphicsResources.createImage(faceBitmap);
                    nextHat = ClientGraphicsResources.createImage(hatBitmap);
                    if (nextFace == null || nextHat == null) return;
                    var resources = getContext().getResources();
                    var faceDrawable = new ImageDrawable(resources, nextFace);
                    var hatDrawable = new ImageDrawable(resources, nextHat);
                    faceDrawable.setFilter(false);
                    hatDrawable.setFilter(false);
                    clearImages();
                    faceView.setImageDrawable(faceDrawable);
                    hatView.setImageDrawable(hatDrawable);
                    faceImage = nextFace;
                    hatImage = nextHat;
                    nextFace = nextHat = null;
                    lastRenderedSkin = skin;
                } finally {
                    ClientGraphicsResources.releaseImage(nextFace);
                    ClientGraphicsResources.releaseImage(nextHat);
                }
            } catch (Exception e) {
                if (!ClientGraphicsResources.RENDER.isStopped()) logger.warn(e);
            }
        } catch (Exception e) {
            if (!ClientGraphicsResources.RENDER.isStopped()) logger.warn(e);
        } finally {
            if (readFromStream && skinImage != null) {
                skinImage.close();
            }
        }
    }

    private void clearImages() {
        faceView.setImageDrawable(null);
        hatView.setImageDrawable(null);
        ClientGraphicsResources.releaseImage(faceImage);
        ClientGraphicsResources.releaseImage(hatImage);
        faceImage = hatImage = null;
    }

    private @NotNull PlayerHeadView.SkinImageResult loadSkinImage() {
        NativeImage skinImage = null;
        Minecraft minecraft = Minecraft.getInstance();
        boolean readFromStream = false;
        AbstractTexture texture = minecraft.getTextureManager().getTexture(skin);
        if (texture instanceof DynamicTexture dt) {
            NativeImage pixels = dt.getPixels();
            if (pixels != null) {
                skinImage = new NativeImage(pixels.getWidth(), pixels.getHeight(), false);
                skinImage.copyFrom(pixels);
                readFromStream = true;
            }
        } else {
            try {
                var resource = minecraft.getResourceManager()
                        .getResource(skin).orElse(null);
                if (resource != null) {
                    try (InputStream stream = resource.open()) {
                        skinImage = NativeImage.read(stream);
                        readFromStream = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new SkinImageResult(skinImage, readFromStream);
    }

    private record SkinImageResult(NativeImage skinImage, boolean readFromStream) {
    }
}
