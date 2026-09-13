package indi.mopelotus.musichud.client.utils.image;

import com.mojang.blaze3d.platform.NativeImage;
import icyllis.modernui.graphics.Bitmap;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

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
        this.cleanable = CLEANER.register(this, () -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                Minecraft.getInstance().submit(texture::close);
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void close() {
        // Cached artwork remains strongly owned by its cache/HUD. Explicit close is for temporary
        // per-request textures; Cleaner.clean is idempotent and uses the render-thread release.
        cleanable.clean();
    }

    public Bitmap convertToBitmap() {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) {
            return null;
        } else {
            return ImageUtils.convertNativeImageToBitmap(pixels);
        }
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