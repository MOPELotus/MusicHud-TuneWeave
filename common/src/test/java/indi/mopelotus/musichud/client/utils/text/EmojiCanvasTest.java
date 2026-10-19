package indi.mopelotus.musichud.client.utils.text;

import icyllis.arc3d.core.*;
import icyllis.arc3d.sketch.TextBlob;
import icyllis.modernui.graphics.*;
import icyllis.modernui.graphics.text.*;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Actual core shaper/canvas and image ownership checks without claiming GPU pixel verification. */
public final class EmojiCanvasTest {

    @org.junit.jupiter.api.Test void actualShaperAndCanvas() throws Exception { checkDrawing(); }
    @org.junit.jupiter.api.Test void resourceOwnership() throws Exception { checkCache(); }
    @org.junit.jupiter.api.Test void sameFontNewEpoch() { checkSameFontReload(); }
    @org.junit.jupiter.api.Test void ownerShutdown() {
        var owner = new indi.mopelotus.musichud.client.utils.OwnedResources(error -> { throw new AssertionError(error); });
        var font = font();
        var loads = new AtomicInteger();
        var cache = owner.create(() -> new EmojiImages(() -> new EmojiImages.Generation(1, font),
                () -> !owner.isStopped(), name -> {
            loads.incrementAndGet();
            return image();
        }));
        Image image = cache.get(font, 1);
        owner.stop();
        check(cache.get(font, 1) == image, "stop-only window may borrow an existing image");
        check(cache.get(font, 3) == null && loads.get() == 1, "stop-only window forbids new image loads");
        owner.close();
        assertThrows(java.util.concurrent.CancellationException.class, () -> owner.create(EmojiImages::new));
        check(image.isClosed() && cache.get(font, 1) == null && loads.get() == 1,
                "existing graphics owner closes images and late draws cannot allocate");
    }
    @org.junit.jupiter.api.Test void completedReloadPublishesMonotonicEpochWithoutGuessingGetterState() {
        var first = WidgetEmojiResources.currentGeneration();
        var font = font();
        WidgetEmojiResources.onResourceReload(font);
        var second = WidgetEmojiResources.currentGeneration();
        WidgetEmojiResources.onResourceReload(font);
        var third = WidgetEmojiResources.currentGeneration();
        check(second.epoch() == first.epoch() + 1 && third.epoch() == second.epoch() + 1,
                "each completed reload advances even if EmojiFont identity is unchanged");
        check(second.font() == font && third.font() == font, "published font is reload callback value");
    }
    @org.junit.jupiter.api.Test void staleDecode() throws Exception { checkLateLoad(); }

    @org.junit.jupiter.api.Test void malformedAndWrongSizeImagesStayNegativeCached() throws Exception {
        var wrongSize = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", wrongSize);
        for (byte[] bytes : new byte[][] {new byte[] {0, 1, 2, 3}, wrongSize.toByteArray()}) {
            var font = font();
            var attempts = new AtomicInteger();
            try (var cache = new EmojiImages(() -> new EmojiImages.Generation(1, font), name -> {
                attempts.incrementAndGet();
                try {
                    return EmojiImages.decode(new java.io.ByteArrayInputStream(bytes));
                } catch (Exception expectedBadResource) {
                    if (bytes.length > 4) check(expectedBadResource instanceof IllegalArgumentException,
                            "wrong-size PNG reaches dimension validation");
                    return null;
                }
            })) {
                check(cache.get(font, 1) == null && cache.get(font, 1) == null && attempts.get() == 1,
                        "actual malformed/wrong-size PNG decoded once per generation");
            }
        }
    }

    @org.junit.jupiter.api.Test void imageCacheIsBoundedAndEvictedImagesAreReleased() {
        var names = new ArrayList<String>();
        for (int i = 0; i < 257; i++) names.add("emoji" + i + ".png");
        var font = new EmojiFont("bounded", new IntOpenHashSet(), 72, 56, 4, 64,
                new Object2IntOpenHashMap<>(), names);
        var all = new ArrayList<Image>();
        try (var cache = new EmojiImages(() -> new EmojiImages.Generation(1, font), name -> {
            var image = image();
            all.add(image);
            return image;
        })) {
            for (int glyph = 1; glyph <= 257; glyph++) cache.get(font, glyph);
            check(all.getFirst().isClosed() && !all.getLast().isClosed(), "LRU eviction releases oldest texture");
        }
        check(all.stream().allMatch(Image::isClosed), "bounded cache close releases every remaining texture");
    }

    @org.junit.jupiter.api.Test void dimensionsAndByteLimitAreCheckedBeforePixelDecodeOrUpload() throws Exception {
        var stream = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", stream);
        byte[] header = stream.toByteArray();
        java.nio.ByteBuffer.wrap(header).putInt(16, 4096).putInt(20, 4096);
        var crc = new java.util.zip.CRC32();
        crc.update(header, 12, 17);
        java.nio.ByteBuffer.wrap(header).putInt(29, (int) crc.getValue());
        var uploads = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> EmojiImages.decode(
                new java.io.ByteArrayInputStream(header), bitmap -> { uploads.incrementAndGet(); return null; }));
        assertThrows(java.io.IOException.class, () -> EmojiImages.decode(
                new java.io.ByteArrayInputStream(new byte[1024 * 1024 + 1]),
                bitmap -> { uploads.incrementAndGet(); return null; }));
        check(uploads.get() == 0, "oversized header/encoded resource never reaches texture allocation");
    }

    @org.junit.jupiter.api.Test void stopDuringDecodePreventsUploadThroughTheExistingOwnerGate() throws Exception {
        var owner = new indi.mopelotus.musichud.client.utils.OwnedResources(error -> { throw new AssertionError(error); });
        var stream = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(72, 72,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", stream);
        var uploads = new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class, () -> EmojiImages.decode(
                new java.io.ByteArrayInputStream(stream.toByteArray()), bitmap -> {
                    owner.stop(); // Main-thread stop completed before the UI's final native allocation.
                    return owner.access(() -> { uploads.incrementAndGet(); return image(); });
                }));
        check(uploads.get() == 0, "atomic owner gate rejects upload after a stop during decode");
        owner.close();
    }

    @org.junit.jupiter.api.Test void retainedPaintAndShapedLayoutUseCurrentImagesAcrossResourceReload() {
        var first = font();
        var second = font();
        var state = new AtomicReference<>(new EmojiImages.Generation(1, first));
        var paint = paint(first, false);
        var retainedLayout = shape("🎵", paint);
        var loadedEpochs = new ArrayList<Long>();
        try (var cache = new EmojiImages(state::get, name -> {
            loadedEpochs.add(state.get().epoch());
            return image();
        })) {
            Image previous = cache.get((EmojiFont) retainedLayout.getFont(0), retainedLayout.getGlyph(0));
            state.set(new EmojiImages.Generation(2, second));
            Image current = cache.get((EmojiFont) retainedLayout.getFont(0), retainedLayout.getGlyph(0));
            check(paint.getFont().getFamilies().getFirst().getClosestMatch(0) == first &&
                    retainedLayout.getFont(0) == first, "same Paint and ShapedText retain the previous font");
            check(previous.isClosed() && current != null && current != previous,
                    "same retained layout replaces its old texture after reload");
            check(current == cache.get(second, 1) && loadedEpochs.equals(List.of(1L, 2L)),
                    "retained layout and new font use only the latest epoch's file image");
        }
    }

    @org.junit.jupiter.api.Test void retainedGlyphIdsResolveTheirOwnFilenamesAndRespectDisabledGeneration() {
        var first = font();
        var second = new EmojiFont("reordered", new IntOpenHashSet(), 72, 56, 4, 64,
                new Object2IntOpenHashMap<>(), List.of("face.png", "note.png", "singer.png", "heart.png"));
        var state = new AtomicReference<>(new EmojiImages.Generation(0, null));
        var loaded = new ArrayList<String>();
        try (var cache = new EmojiImages(state::get, name -> { loaded.add(name); return image(); })) {
            check(cache.get(first, 1) == null && loaded.isEmpty(), "no resource publication does not guess an active font");
            state.set(new EmojiImages.Generation(1, first));
            Image old = cache.get(first, 1);
            state.set(new EmojiImages.Generation(2, second));
            Image current = cache.get(first, 1);
            check(old.isClosed() && current != null && current == cache.get(second, 2),
                    "glyph IDs resolve through their layout's font despite current font reordering");
            check(loaded.equals(List.of("note.png", "note.png")), "filename cache never substitutes the new font's different glyph ID");
            state.set(new EmojiImages.Generation(3, null));
            check(cache.get(first, 1) == null && current.isClosed() && loaded.size() == 2,
                    "disabled/no-resource current generation rejects even retained fonts");
        }
    }

    private static EmojiFont font() {
        var map = new Object2IntOpenHashMap<CharSequence>();
        var coverage = new IntOpenHashSet();
        String[] sequences = {"🎵", "😀", "👩‍🎤", "❤️"};
        for (int i = 0; i < sequences.length; i++) {
            map.put(sequences[i], i + 1);
            sequences[i].codePoints().forEach(coverage::add);
        }
        return new EmojiFont("test emoji", coverage, 72, 56, 4, 64, map,
                List.of("note.png", "face.png", "singer.png", "heart.png"));
    }

    private static FontPaint paint(EmojiFont font, boolean linear) {
        var paint = new FontPaint();
        paint.setFont(new FontCollection(new FontFamily(font), FontFamily.SANS_SERIF));
        paint.setFontSize(17);
        paint.setLocale(java.util.Locale.ROOT);
        paint.setLinearMetrics(linear);
        return paint;
    }

    private static ShapedText shape(String value, FontPaint paint) {
        char[] chars = value.toCharArray();
        return new ShapedText(chars, 0, chars.length, 0, chars.length, ShapedText.BIDI_LTR, paint);
    }

    private static void checkDrawing() throws Exception {
        var font = font();
        var loaded = new ArrayList<String>();
        var owned = new ArrayList<Image>();
        try (var images = new EmojiImages(() -> new EmojiImages.Generation(0, font), name -> {
            loaded.add(name);
            Image image = image();
            owned.add(image);
            return image;
        }); var nativeCanvas = new Capture()) {
            var canvas = new EmojiAwareCanvas(nativeCanvas, images);
            var textPaint = new Paint();
            textPaint.setColor(0x607f0022);
            textPaint.setShader(new LinearGradient(0, 0, 20, 0, 0x3bff0000, 0xff0000ff,
                    Shader.TileMode.CLAMP, null));
            long originalColor = textPaint.getColorLong();
            var fontPaint = paint(font, false);
            var shaped = shape("🎵👩‍🎤❤️", fontPaint);
            check(shaped.getGlyphCount() == 3, "ZWJ and VS remain one shaped glyph each");
            new icyllis.modernui.graphics.pipeline.ArcCanvas(nativeCanvas).drawShapedText(shaped, 10, 40, textPaint);
            check(nativeCanvas.images.isEmpty(), "actual unpatched core drops the emoji image pass");
            canvas.save();
            canvas.translate(2.25f, 3.5f);
            canvas.scale(1.2f, 0.8f);
            canvas.clipRect(0, 0, 500, 200);
            Matrix4 before = canvas.getMatrix();
            RectF clipBefore = new RectF();
            canvas.getLocalClipBounds(clipBefore);
            int saveCount = canvas.getSaveCount();
            nativeCanvas.expectedAlphaShader = textPaint.getNativePaint().getShader();
            canvas.drawShapedText(shaped, 10, 40, textPaint);
            check(nativeCanvas.images.size() == 3, "shaped text draws each emoji once");
            check(nativeCanvas.blobs == 0, "no null-native emoji blob drawn");
            check(loaded.equals(List.of("note.png", "singer.png", "heart.png")), "glyph IDs resolve complete sequences");
            var first = nativeCanvas.images.getFirst();
            near(first[0], 10 + shaped.getX(0), "no extra emoji spacing");
            near(first[1], 40 + shaped.getY(0) - 15, "rounded baseline");
            near(first[2] - first[0], 20, "rounded font image size");
            near(first[4], textPaint.getAlphaF(), "text opacity reaches color image");
            check(first[5] == 1, "DST_IN uses original gradient alpha as source and color PNG as destination");
            check(textPaint.getColorLong() == originalColor && textPaint.hasShader(), "caller paint unchanged");
            check(canvas.getMatrix().equals(before) && canvas.getSaveCount() == saveCount,
                    "drawing preserves active transform, clip and save stack");
            check(nativeCanvas.transforms.getFirst().equals(before), "image uses widget transform");
            RectF clipAfter = new RectF();
            canvas.getLocalClipBounds(clipAfter);
            check(clipBefore.equals(clipAfter), "image pass preserves clip bounds");
            canvas.restore();

            nativeCanvas.reset();
            canvas.drawTextRun("A🎵B👩‍🎤C", 0, 10, 0, 10, 10, 40, false, textPaint, fontPaint);
            check(nativeCanvas.images.size() == 2 && nativeCanvas.blobs == 1, "mixed CharSequence native once and emoji once");
            nativeCanvas.reset();
            String context = "x🎵👩‍🎤y";
            canvas.drawTextRun(context, 1, context.length() - 1, 0, context.length(), 10, 40, false, textPaint, fontPaint);
            check(nativeCanvas.images.size() == 2 && nativeCanvas.blobs == 0, "context range respects offsets without duplicate pass");
            nativeCanvas.reset();
            char[] chars = "abc".toCharArray();
            canvas.drawTextRun(chars, 0, chars.length, 0, chars.length, 0, 40, false, textPaint, fontPaint);
            check(nativeCanvas.images.isEmpty() && nativeCanvas.blobs == 1, "outline-only text unchanged");
            nativeCanvas.reset();
            canvas.drawGlyphs(new int[]{99, 1, 3}, 1, new float[]{99, 99, 5, 2, 40, 3}, 2, 2,
                    font, 10, 40, textPaint, paint(font, true));
            check(nativeCanvas.images.size() == 2, "direct glyph offsets/count honored");
            first = nativeCanvas.images.getFirst();
            near(first[0], 15, "glyph position offset");
            near(first[1], 42 - 14.875f, "linear baseline");
            near(first[2] - first[0], 19.125f, "linear font size");
            nativeCanvas.reset();
            canvas.drawGlyphs(new int[]{0}, 0, new float[]{0, 0}, 0, 1, font, 0, 0, textPaint, fontPaint);
            canvas.drawGlyphs(new int[0], 0, new float[0], 0, 0, font, 0, 0, textPaint, fontPaint);
            check(nativeCanvas.images.isEmpty(), "zero glyphs do not draw or load resources");
            assertThrows(IndexOutOfBoundsException.class, () -> canvas.drawTextRun("abc", 0, 4, 0, 4,
                    0, 0, false, textPaint, fontPaint));
            assertThrows(IndexOutOfBoundsException.class, () -> canvas.drawGlyphs(new int[]{1}, 0,
                    new float[]{0}, 0, 1, font, 0, 0, textPaint, fontPaint));
            var plain = new Paint();
            plain.setColor(0x3bff0000);
            nativeCanvas.reset();
            canvas.drawShapedText(shaped, 10, 40, plain);
            check(nativeCanvas.images.size() == 3 && nativeCanvas.images.getFirst()[5] == 0,
                    "no-shader color images use ordinary image draw once");
            near(nativeCanvas.images.getFirst()[4], plain.getAlphaF(), "no-shader opacity preserved");
            plain.reset();
            textPaint.reset();
        }
        check(owned.stream().allMatch(Image::isClosed), "drawing cache releases its actual images");
    }

    private static void checkCache() throws Exception {
        var oldFont = font();
        var newFont = font();
        var selected = new AtomicReference<>(oldFont);
        var loads = new AtomicInteger();
        var owned = new ArrayList<Image>();
        var cache = new EmojiImages(() -> new EmojiImages.Generation(0, selected.get()), name -> {
            loads.incrementAndGet();
            if (name.equals("face.png")) return null;
            Image image = image();
            owned.add(image);
            return image;
        });
        Image old = cache.get(oldFont, 1);
        check(old == cache.get(oldFont, 1) && loads.get() == 1, "image cached per generation");
        check(cache.get(oldFont, 2) == null && cache.get(oldFont, 2) == null && loads.get() == 2,
                "bad/missing resource negative cached");
        selected.set(newFont);
        cache.synchronize();
        check(old.isClosed(), "resource reload closes previous image");
        Image retained = cache.get(oldFont, 1);
        check(retained != null && retained != old && loads.get() == 3,
                "retained old layout loads a new image from the current generation");
        check(cache.get(newFont, 2) == null && loads.get() == 4, "negative cache invalidated by new generation");
        Image current = cache.get(newFont, 1);
        check(current == retained, "old and current fonts share current-epoch filename cache");
        cache.synchronize(); // An older queued reload task reads the authoritative current font.
        check(current == cache.get(newFont, 1) && !current.isClosed(), "late invalidation retains current generation");
        cache.close();
        cache.close();
        int before = loads.get();
        check(current.isClosed() && owned.stream().allMatch(Image::isClosed), "exit closes all owned images idempotently");
        check(cache.get(newFont, 1) == null && loads.get() == before, "late draw after exit cannot allocate");
    }

    private static void checkSameFontReload() {
        var font = font();
        var state = new AtomicReference<>(new EmojiImages.Generation(1, font));
        var loads = new AtomicInteger();
        try (var cache = new EmojiImages(state::get, name -> {
            loads.incrementAndGet();
            return name.equals("face.png") ? null : image();
        })) {
            Image first = cache.get(font, 1);
            cache.get(font, 2);
            cache.get(font, 2);
            check(loads.get() == 2, "same epoch caches failed resource");
            state.set(new EmojiImages.Generation(2, font));
            cache.synchronize();
            check(first.isClosed(), "same font identity new epoch closes old PNG");
            Image second = cache.get(font, 1);
            cache.get(font, 2);
            check(second != first && loads.get() == 4, "same font new epoch reloads images and failures");
        }
    }

    private static void checkLateLoad() throws Exception {
        var first = font();
        var second = font();
        var selected = new AtomicReference<>(first);
        var result = new AtomicReference<Image>();
        try (var cache = new EmojiImages(() -> new EmojiImages.Generation(0, selected.get()), name -> {
            Image image = image();
            result.set(image);
            selected.set(second); // A resource reload finished while an image was decoded.
            return image;
        })) {
            check(cache.get(first, 1) == null && result.get().isClosed(), "late decoded image rejected and closed");
            Image current = cache.get(first, 1);
            check(current != null && !current.isClosed() && current == cache.get(second, 1),
                    "retained font retries against current epoch without rolling it back");
        }
    }

    private static Image image() {
        try {
            var constructor = Image.class.getDeclaredConstructor(icyllis.arc3d.sketch.Image.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new TestImage());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class TestImage extends icyllis.arc3d.sketch.Image {
        TestImage() { super(ImageInfo.make(72, 72, ColorInfo.CT_RGBA_8888, ColorInfo.AT_PREMUL, null)); }
        @Override protected void deallocate() {}
    }

    private static final class Capture extends icyllis.arc3d.sketch.Canvas {
        final ArrayList<float[]> images = new ArrayList<>();
        final ArrayList<Matrix4> transforms = new ArrayList<>();
        int blobs;
        icyllis.arc3d.sketch.shaders.Shader expectedAlphaShader;
        Capture() { super(1000, 1000); }
        void reset() { images.clear(); transforms.clear(); blobs = 0; }
        @Override protected void onDrawImageRect(icyllis.arc3d.sketch.Image image, Rect2fc src, Rect2fc dst,
                                                 SamplingOptions sampling, icyllis.arc3d.sketch.Paint paint, int constraint) {
            images.add(new float[]{dst.left(), dst.top(), dst.right(), dst.bottom(), paint.getAlpha(), paint.getShader() == null ? 0 : 1});
            var matrix = new Matrix4();
            getLocalToDevice(matrix);
            transforms.add(matrix);
        }
        @Override protected void onDrawRect(Rect2fc dst, icyllis.arc3d.sketch.Paint paint) {
            var shader = paint.getShader();
            boolean alphaOnly = shader instanceof icyllis.arc3d.sketch.shaders.BlendShader blend &&
                    blend.getMode() == icyllis.arc3d.sketch.BlendMode.DST_IN && blend.getSrc() == expectedAlphaShader &&
                    blend.getDst() != expectedAlphaShader;
            images.add(new float[]{dst.left(), dst.top(), dst.right(), dst.bottom(), paint.getAlpha(), alphaOnly ? 1 : 0});
            var matrix = new Matrix4();
            getLocalToDevice(matrix);
            transforms.add(matrix);
        }
        @Override protected void onDrawTextBlob(TextBlob blob, float x, float y, icyllis.arc3d.sketch.Paint paint) {
            if (blob != null) blobs++;
        }
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.0001, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
