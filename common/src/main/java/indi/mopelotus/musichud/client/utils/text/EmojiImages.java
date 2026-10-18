/*
 * Modern UI. Licensed under LGPL-3.0-or-later; see LICENSE.
 */
package indi.mopelotus.musichud.client.utils.text;

import icyllis.modernui.graphics.BitmapFactory;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.Bitmap;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import java.util.function.BooleanSupplier;
import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.mc.text.GlyphManager;
import indi.mopelotus.musichud.MusicHud;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** UI-thread-owned images for the current resource-pack emoji font, including failed lookups. */
final class EmojiImages implements AutoCloseable {
    private static final int MAX_IMAGES = 256;
    // 72px RGBA is about 20 KiB; allow ample encoding/metadata overhead without an unbounded resource read.
    private static final int MAX_PNG_BYTES = 1024 * 1024;
    record Generation(long epoch, EmojiFont font) {}
    private final Supplier<Generation> currentGeneration;
    private final Function<String, Image> loader;
    private final BooleanSupplier canAllocate;
    private final LinkedHashMap<String, Image> images = new LinkedHashMap<>(16, 0.75f, true);
    private Generation generation;
    private boolean closed;

    EmojiImages() {
        this(WidgetEmojiResources::currentGeneration, () -> !ClientGraphicsResources.isUiStopped(), EmojiImages::load);
    }

    EmojiImages(Supplier<Generation> currentGeneration, Function<String, Image> loader) {
        this(currentGeneration, () -> true, loader);
    }

    EmojiImages(Supplier<Generation> currentGeneration, BooleanSupplier canAllocate, Function<String, Image> loader) {
        this.canAllocate = canAllocate;
        this.currentGeneration = currentGeneration;
        this.loader = loader;
    }

    void synchronize() {
        Generation current = currentGeneration.get();
        if (!closed && !java.util.Objects.equals(generation, current)) {
            clear();
            generation = current;
        }
    }

    Image get(EmojiFont font, int glyphId) {
        synchronize();
        // Retained TextViews may still own an older FontPaint. Its glyph IDs resolve filenames only;
        // the published current epoch alone selects resource images and invalidates old textures.
        if (closed || generation == null || generation.font() == null || font == null || glyphId <= 0) return null;
        String name = font.getFileName(glyphId);
        if (images.containsKey(name)) return images.get(name);
        if (!canAllocate.getAsBoolean()) return null;
        Image image = loader.apply(name);
        if (closed || !java.util.Objects.equals(currentGeneration.get(), generation)) {
            if (image != null) image.close();
            synchronize();
            return null;
        }
        images.put(name, image);
        if (images.size() > MAX_IMAGES) {
            Image evicted = images.remove(images.firstEntry().getKey());
            if (evicted != null) evicted.close();
        }
        return image;
    }

    private static Image load(String name) {
        var location = Identifier.fromNamespaceAndPath("modernui", "emoji/" + name);
        try (var stream = Minecraft.getInstance().getResourceManager().open(location)) {
            return decode(stream, bitmap -> ClientGraphicsResources.accessUiResources(
                    () -> Image.createTextureFromBitmap(bitmap)));
        } catch (java.util.concurrent.CancellationException stopping) {
            return null;
        } catch (Exception failure) {
            MusicHud.LOGGER.warn("Failed to load widget emoji {}", location, failure);
            return null;
        }
    }

    static Image decode(java.io.InputStream stream) throws java.io.IOException {
        return decode(stream, Image::createTextureFromBitmap);
    }

    static Image decode(java.io.InputStream stream, Function<Bitmap, Image> upload) throws java.io.IOException {
        byte[] bytes = stream.readNBytes(MAX_PNG_BYTES + 1);
        if (bytes.length > MAX_PNG_BYTES) throw new java.io.IOException("Emoji PNG exceeds 1 MiB");
        var options = new BitmapFactory.Options();
        BitmapFactory.decodeByteArrayInfo(bytes, 0, bytes.length, options);
        if (options.outWidth != GlyphManager.EMOJI_SIZE || options.outHeight != GlyphManager.EMOJI_SIZE) {
            throw new IllegalArgumentException("Expected a " + GlyphManager.EMOJI_SIZE + "px emoji image");
        }
        options.inPreferredFormat = Bitmap.Format.RGBA_8888;
        try (var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options)) {
            if (bitmap == null) throw new java.io.IOException("Could not decode emoji PNG");
            return upload.apply(bitmap);
        }
    }

    private void clear() {
        for (Image image : images.values()) if (image != null) image.close();
        images.clear();
    }

    @Override
    public void close() {
        closed = true;
        clear();
        generation = null;
    }
}
