package indi.mopelotus.musichud.client.utils.text;

import icyllis.arc3d.core.*;
import icyllis.arc3d.sketch.Surface;
import icyllis.arc3d.sketch.TextBlob;
import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.pipeline.ArcCanvas;
import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.text.*;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.client.ui.lyric.LineBlurRenderer;
import indi.mopelotus.musichud.client.ui.lyric.LineBlurResources;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual blur control flow and actual widget drawing on recording CPU surfaces; no GPU pixel claim. */
class BlurEmojiTest {
    @Test void actualOffscreenEntryDrawsMixedAndPureEmojiOnceAndPreservesTransforms() throws Exception {
        try (var fixture = new Fixture()) {
            var draws = new AtomicInteger();
            var mixed = fixture.boring("A😀B");
            var pure = fixture.highlighted("👩‍🎤");
            LineBlurRenderer.ContentDrawer content = canvas -> {
                draws.incrementAndGet();
                assertInstanceOf(EmojiAwareCanvas.class, canvas, "offscreen entry must use the shared widget bridge");
                int beforeImages = fixture.source.canvas.imageDraws;
                int beforeBlobs = fixture.source.canvas.blobs;
                mixed.draw(canvas);
                assertEquals(beforeImages + 1, fixture.source.canvas.imageDraws, "mixed BoringLayout draws one emoji");
                assertEquals(beforeBlobs + 1, fixture.source.canvas.blobs, "mixed native text draws exactly once");
                beforeImages = fixture.source.canvas.imageDraws;
                beforeBlobs = fixture.source.canvas.blobs;
                pure.draw(canvas);
                assertEquals(beforeImages + 1, fixture.source.canvas.imageDraws, "production pure-emoji HighlightSpan draws once");
                assertEquals(beforeBlobs, fixture.source.canvas.blobs, "pure emoji does not invent an outline blob");
            };
            fixture.main.translate(3, 7);
            var mainMatrix = new Matrix4();
            fixture.main.getLocalToDevice(mainMatrix);
            int saves = fixture.main.getSaveCount();
            assertTrue(fixture.draw(123, content));
            assertEquals(1, draws.get());
            assertEquals(2, fixture.loads.size());
            assertEquals(2, fixture.source.canvas.imageDraws);
            assertEquals(1, fixture.source.canvas.blobs);
            assertEquals(19, fixture.scratch.canvas.imageDraws, "actual horizontal convolution taps");
            assertEquals(19, fixture.result.canvas.imageDraws, "actual vertical convolution taps");
            assertEquals(1, fixture.main.imageDraws, "one final composite");
            for (var transform : fixture.source.canvas.textTransforms) {
                assertEquals(0.5f, transform.m11(), 0.0001f);
                assertEquals(0.5f, transform.m22(), 0.0001f);
                assertEquals(11f, transform.m41(), 0.0001f);
                assertEquals(11f, transform.m42(), 0.0001f);
            }
            var after = new Matrix4();
            fixture.main.getLocalToDevice(after);
            assertEquals(mainMatrix, after);
            assertEquals(saves, fixture.main.getSaveCount());
            fixture.source.canvas.getLocalToDevice(after);
            assertTrue(after.isIdentity(), "offscreen transform is restored");
            assertTrue(fixture.draw(123, content));
            assertEquals(1, draws.get(), "same stamp and epoch reuse the actual rendered source");
            assertEquals(2, fixture.main.imageDraws);
            assertEquals(19, fixture.scratch.canvas.imageDraws, "cached source/radius skips convolution too");
            fixture.resources.close();
            fixture.assertBlurReleased();
            assertTrue(fixture.loads.stream().noneMatch(Image::isClosed), "blur never owns the shared emoji cache");
        }
    }

    @Test void retainedLayoutsRerenderAcrossSameAndNewFontEpochsAndStopCannotAllocate() throws Exception {
        try (var fixture = new Fixture()) {
            var layout = fixture.boring("A😀B👩‍🎤");
            var draws = new AtomicInteger();
            LineBlurRenderer.ContentDrawer content = canvas -> { draws.incrementAndGet(); layout.draw(canvas); };
            long epoch = WidgetEmojiResources.currentEpoch();
            assertTrue(fixture.draw(456, content));
            assertEquals(2, fixture.loads.size());
            List<Image> old = List.copyOf(fixture.loads);
            fixture.publish(epoch + 1, fixture.font); // Same retained layout, font, row stamp and radius.
            assertTrue(fixture.draw(456, content));
            assertEquals(2, draws.get());
            assertEquals(4, fixture.loads.size());
            assertTrue(old.stream().allMatch(Image::isClosed));
            old = List.copyOf(fixture.loads);
            fixture.publish(epoch + 2, font());
            assertTrue(fixture.draw(456, content));
            assertEquals(3, draws.get(), "new font epoch invalidates a stable row's offscreen source");
            assertEquals(6, fixture.loads.size(), "retained old-font glyphs resolve current-epoch images");
            assertTrue(old.stream().allMatch(Image::isClosed));
            assertTrue(fixture.draw(456, content));
            assertEquals(3, draws.get());
            fixture.canAllocate.set(false);
            fixture.publish(epoch + 3, fixture.font);
            assertTrue(fixture.draw(456, content));
            assertEquals(4, draws.get());
            assertEquals(6, fixture.loads.size(), "stopped shared cache cannot allocate during a late offscreen frame");
            assertSame(fixture.images, field(WidgetEmojiResources.class, "images").get(null),
                    "offscreen bridge reuses the same existing cache; it never installs a second owner");
            assertTrue(fixture.loads.stream().allMatch(Image::isClosed), "epoch change releases old cache images even after stop");
            fixture.resources.close();
            fixture.assertBlurReleased();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EmojiFont font = font();
        final AtomicBoolean canAllocate = new AtomicBoolean(true);
        final List<Image> loads = new ArrayList<>();
        final EmojiImages images;
        final LineBlurResources resources = new LineBlurResources();
        final FakeSurface source = new FakeSurface(122, 62);
        final FakeSurface scratch = new FakeSurface(122, 62);
        final FakeSurface result = new FakeSurface(122, 62);
        final Capture main = new Capture(1000, 1000);
        final ArcCanvas canvas = new ArcCanvas(main);
        final Object previousThread;
        final Object previousImages;
        final Object previousGeneration;
        Fixture() throws Exception {
            previousThread = field(Core.class, "sUiThread").get(null);
            previousImages = field(WidgetEmojiResources.class, "images").get(null);
            previousGeneration = field(WidgetEmojiResources.class, "generation").get(null);
            images = new EmojiImages(WidgetEmojiResources::currentGeneration, canAllocate::get, name -> {
                var image = image();
                loads.add(image);
                return image;
            });
            field(Core.class, "sUiThread").set(null, Thread.currentThread());
            field(WidgetEmojiResources.class, "images").set(null, images);
            publish(WidgetEmojiResources.currentEpoch() + 1, font);
            setResource("sourceSurface", source);
            setResource("scratchSurface", scratch);
            setResource("finalSurface", result);
            setResource("surfaceWidth", 122);
            setResource("surfaceHeight", 62);
            setResource("downscale", 0.5f);
        }
        void publish(long epoch, EmojiFont selected) throws Exception {
            // Inject a completed publication without a UI Handler: this test does not start Core's UI loop.
            field(WidgetEmojiResources.class, "generation").set(null, new EmojiImages.Generation(epoch, selected));
        }
        void setResource(String name, Object value) throws Exception { field(LineBlurResources.class, name).set(resources, value); }
        boolean draw(long stamp, LineBlurRenderer.ContentDrawer content) {
            return LineBlurRenderer.drawBlurred(canvas, 200, 80, 12, stamp, resources, content);
        }
        TextPaint paint() {
            var paint = new TextPaint();
            paint.setTypeface(Typeface.createTypeface(new FontFamily(font), FontFamily.SANS_SERIF));
            paint.setTextSize(17);
            return paint;
        }
        BoringLayout boring(String text) {
            var paint = paint();
            var metrics = BoringLayout.isBoring(text, paint, null);
            assertNotNull(metrics);
            return BoringLayout.make(text, paint, 200, Layout.Alignment.ALIGN_NORMAL, 1, 0, metrics, false);
        }
        StaticLayout highlighted(String text) {
            var duration = Duration.ofSeconds(4);
            var lyric = LyricLine.builder().text(text).startTime(Duration.ZERO).duration(duration)
                    .wordByWord(true).phraseEndingOffsetMap(Map.of(duration, text.length())).build();
            lyric.parsePhrases();
            var source = lyric.getSpannableString();
            return StaticLayout.builder(source, 0, source.length(), paint(), 200).build();
        }
        void assertBlurReleased() {
            for (var surface : List.of(source, scratch, result)) {
                assertTrue(surface.released);
                assertFalse(surface.snapshots.isEmpty());
                assertTrue(surface.snapshots.stream().allMatch(image -> image.released), "all cached/source/convolution snapshots are released");
            }
        }
        @Override public void close() throws Exception {
            try {
                resources.close();
                images.close();
                assertTrue(loads.stream().allMatch(Image::isClosed));
                main.close();
            } finally {
                field(WidgetEmojiResources.class, "images").set(null, previousImages);
                field(WidgetEmojiResources.class, "generation").set(null, previousGeneration);
                field(Core.class, "sUiThread").set(null, previousThread);
            }
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static EmojiFont font() {
        var coverage = new IntOpenHashSet();
        var map = new Object2IntOpenHashMap<CharSequence>();
        String[] sequences = {"😀", "👩‍🎤"};
        for (int i = 0; i < sequences.length; i++) {
            map.put(sequences[i], i + 1);
            sequences[i].codePoints().forEach(coverage::add);
        }
        return new EmojiFont("blur fixture emoji", coverage, 72, 56, 4, 64, map,
                List.of("face.png", "singer.png"));
    }
    private static Image image() {
        try {
            var constructor = Image.class.getDeclaredConstructor(icyllis.arc3d.sketch.Image.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new FakeImage(72, 72));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class FakeImage extends icyllis.arc3d.sketch.Image {
        boolean released;
        FakeImage(int width, int height) { super(info(width, height)); }
        @Override protected void deallocate() { released = true; }
    }
    private static ImageInfo info(int width, int height) {
        return ImageInfo.make(width, height, ColorInfo.CT_RGBA_8888, ColorInfo.AT_PREMUL, null);
    }
    private static final class FakeSurface extends Surface {
        final Capture canvas;
        final List<FakeImage> snapshots = new ArrayList<>();
        boolean released;
        FakeSurface(int width, int height) { super(width, height); canvas = new Capture(width, height); }
        @Override public ImageInfo getImageInfo() { return info(getWidth(), getHeight()); }
        @Override protected icyllis.arc3d.sketch.Canvas onNewCanvas() { return canvas; }
        @Override protected icyllis.arc3d.sketch.Image onNewImageSnapshot(Rect2ic subset) {
            var image = new FakeImage(getWidth(), getHeight());
            snapshots.add(image);
            return image;
        }
        @Override protected boolean onCopyOnWrite(int mode) { return true; }
        @Override protected void deallocate() { super.deallocate(); released = true; }
    }
    private static final class Capture extends icyllis.arc3d.sketch.Canvas {
        int imageDraws, blobs;
        final List<Matrix4> textTransforms = new ArrayList<>();
        Capture(int width, int height) { super(width, height); }
        void recordTransform() {
            var matrix = new Matrix4();
            getLocalToDevice(matrix);
            textTransforms.add(matrix);
        }
        @Override protected void onDrawImageRect(icyllis.arc3d.sketch.Image image, Rect2fc src, Rect2fc dst,
                SamplingOptions sampling, icyllis.arc3d.sketch.Paint paint, int constraint) {
            imageDraws++;
            recordTransform();
        }
        @Override protected void onDrawTextBlob(TextBlob blob, float x, float y, icyllis.arc3d.sketch.Paint paint) {
            if (blob != null) { blobs++; recordTransform(); }
        }
    }
}
