package indi.mopelotus.musichud.client.utils.image;

import com.mojang.blaze3d.platform.NativeImage;
import icyllis.modernui.graphics.Bitmap;
import lombok.Getter;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.util.Objects;

@Getter
public final class ImageTextureData implements Closeable {
    private final String source;
    private final DynamicTexture texture;
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;

    public ImageTextureData(
            String source, DynamicTexture texture
    ) {
        this.source = source;
        this.texture = texture;
        this.cleanable = CLEANER.register(this, () -> ClientGraphicsResources.releaseTexture(texture));
    }

    @Override
    public void close() {
        // Cached artwork remains strongly owned by its cache/HUD. Explicit close is for temporary
        // per-request textures; Cleaner.clean is idempotent and uses the render-thread release.
        cleanable.clean();
    }

    public Bitmap convertToBitmap() {
        return ClientGraphicsResources.RENDER.access(() -> {
            NativeImage pixels = texture.getPixels();
            if (pixels == null) return null;
            // A UI upload may outlive render-thread shutdown. Return owned pixels, not a borrowed native pointer.
            try (Bitmap wrapped = ImageUtils.convertNativeImageToBitmap(pixels)) {
                Bitmap copy = Bitmap.createBitmap(pixels.getWidth(), pixels.getHeight(), Bitmap.Format.RGBA_8888);
                try {
                    copy.setPixels(wrapped, 0, 0, 0, 0, pixels.getWidth(), pixels.getHeight());
                    return copy;
                } catch (RuntimeException | Error error) {
                    copy.close();
                    throw error;
                }
            }
        });
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ImageTextureData) obj;
        return Objects.equals(this.source, that.source) &&
                Objects.equals(this.texture, that.texture);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, texture);
    }

    @Override
    public String toString() {
        return "ImageTextureData[" +
                "source=" + source + ", " +
                "texture=" + texture + ']';
    }
}