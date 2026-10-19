package indi.mopelotus.musichud.client.utils.text;

import icyllis.arc3d.core.*;
import icyllis.arc3d.sketch.TextBlob;
import icyllis.modernui.graphics.*;
import icyllis.modernui.graphics.text.*;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import java.lang.invoke.MethodHandles;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CPU regression through the real core StaticLayout -> Layout -> TextLine chain.
 * The explicitly woven fixture is NOT a claim that the Mixin engine applied the production mixin.
 * It preserves core shaping/native drawing/decorations and inserts only the production bridge calls.
 */
public class TextLineEmojiTest {
    private static final String TEXT = "icyllis.modernui.text.";
    private static final String SELF = TextLineEmojiTest.class.getName().replace('.', '/');
    private static final String CONSUMER = "Licyllis/modernui/graphics/text/ShapedText$RunConsumer;";
    private static final String LIST = "Ljava/util/List;";

    @Test void actualStaticLayoutAllEmojiAndMixedNativePass() throws Exception {
        try (var fixture = new Fixture()) {
            Object emoji = fixture.layout("🎵👩‍🎤", false, false, 0);
            assertEquals(0, fixture.loads.get(), "measurement must not allocate image resources");
            fixture.draw(emoji);
            assertEquals(2, fixture.capture.images.size(), "actual TextLine must draw null-blob emoji runs");
            assertEquals(0, fixture.capture.blobs);
            fixture.capture.reset();
            fixture.draw(fixture.layout("A🎵B", false, false, 0));
            assertEquals(1, fixture.capture.images.size());
            assertEquals(1, fixture.capture.blobs, "original native blob must be drawn exactly once");
            fixture.capture.reset();
            fixture.draw(fixture.layout("ABC", false, false, 0));
            assertEquals(0, fixture.capture.images.size());
            assertEquals(1, fixture.capture.blobs);
        }
    }

    @Test void actualRtlPositionBaselineAndDecorations() throws Exception {
        try (var fixture = new Fixture()) {
            Object layout = fixture.layout("🎵", true, true, 7);
            float right = ((Number) layout.getClass().getMethod("getLineRight", int.class).invoke(layout, 0)).floatValue();
            float baseline = ((Number) layout.getClass().getMethod("getLineBaseline", int.class).invoke(layout, 0)).floatValue();
            fixture.draw(layout);
            assertEquals(1, fixture.capture.images.size());
            float[] image = fixture.capture.images.getFirst();
            // Glyph positions include the emoji spacing, while the RTL run starts at right - advance.
            var fp = ((icyllis.modernui.text.TextPaint) layout.getClass().getMethod("getPaint").invoke(layout)).getInternalPaint();
            char[] chars = "🎵".toCharArray();
            var shaped = new ShapedText(chars, 0, chars.length, 0, chars.length, ShapedText.BIDI_RTL, fp);
            assertEquals(right - shaped.getAdvance() + shaped.getX(0), image[0], 0.001f);
            float ascent = 56f / 64 * fp.getFontSize();
            if (!fp.isLinearMetrics()) ascent = (int) (0.95d + ascent);
            assertEquals(baseline + 7 - ascent + shaped.getY(0), image[1], 0.001f);
            assertEquals(3, fixture.capture.rects, "background, underline and strike each remain one core pass");
        }
    }

    @Test void exceptionAndNestedDrawingDoNotLeakRuns() throws Exception {
        try (var fixture = new Fixture()) {
            Object outer = fixture.layout("🎵", false, false, 0);
            Object inner = fixture.layout("👩‍🎤", false, false, 0);
            fixture.capture.onImage = () -> {
                fixture.capture.onImage = null;
                try { fixture.draw(inner); } catch (Exception failure) { throw new AssertionError(failure); }
            };
            fixture.draw(outer);
            assertEquals(2, fixture.capture.images.size(), "nested layout has its own invocation-local run list");
            fixture.capture.reset();
            fixture.capture.onImage = () -> { throw new IllegalStateException("injected image draw failure"); };
            assertThrows(InvocationTargetException.class, () -> fixture.draw(outer));
            fixture.capture.onImage = null;
            fixture.capture.reset();
            fixture.draw(fixture.layout("A", false, false, 0));
            assertTrue(fixture.capture.images.isEmpty(), "failed prior draw cannot contaminate another layout");
            assertEquals(1, fixture.capture.blobs);
            fixture.capture.reset();
            fixture.draw(inner);
            assertEquals(1, fixture.capture.images.size(), "later emoji draw remains usable after failure");
        }
    }

    @Test void measurementConsumerStaysNullAndRunMetricsAreFrozen() {
        assertNull(EmojiAwareCanvas.collectEmojiRuns(null, run -> fail("measurement collected a run")));
        var font = font();
        var fp = fontPaint(font);
        var runs = new ArrayList<EmojiAwareCanvas.EmojiRun>();
        var nativeCalls = new AtomicInteger();
        char[] text = "🎵".toCharArray();
        ShapedText.doLayoutRun(text, 0, text.length, 0, text.length, false, fp, null,
                EmojiAwareCanvas.collectEmojiRuns((piece, x, paint) -> {
                    nativeCalls.incrementAndGet();
                    paint.setFontSize(100);
                }, runs::add));
        assertEquals(1, nativeCalls.get());
        assertEquals(1, runs.size());
        fp.setFontSize(100);
        assertEquals(17, runs.getFirst().fontSize());
        assertFalse(runs.getFirst().linearMetrics());
    }

    @Test void actualBoringAndProductionReplacementSpanDoNotDoubleDraw() throws Exception {
        try (var fixture = new Fixture()) {
            var paint = new icyllis.modernui.text.TextPaint();
            paint.setTypeface(icyllis.modernui.text.Typeface.createTypeface(new FontFamily(fixture.font), FontFamily.SANS_SERIF));
            paint.setTextSize(17);
            String text = "A🎵👩‍🎤";
            var metrics = icyllis.modernui.text.BoringLayout.isBoring(text, paint, null);
            assertNotNull(metrics);
            var boring = icyllis.modernui.text.BoringLayout.make(text, paint, 300,
                    icyllis.modernui.text.Layout.Alignment.ALIGN_NORMAL, 1, 0, metrics, false);
            boring.draw(fixture.canvas);
            assertEquals(2, fixture.capture.images.size(), "actual BoringLayout uses exactly one image pass");
            assertEquals(1, fixture.capture.blobs);
            fixture.capture.reset();
            var duration = java.time.Duration.ofSeconds(4);
            var lyric = indi.mopelotus.musichud.client.ui.dto.LyricLine.builder().text(text)
                    .startTime(java.time.Duration.ZERO).duration(duration).wordByWord(true)
                    .phraseEndingOffsetMap(Map.of(duration, text.length())).build();
            lyric.parsePhrases();
            var source = lyric.getSpannableString();
            var layout = icyllis.modernui.text.StaticLayout.builder(source, 0, source.length(), paint, 300).build();
            layout.draw(fixture.canvas);
            assertEquals(2, fixture.capture.images.size(), "actual production HighlightSpan keeps ZWJ intact and draws each image once");
            assertEquals(1, fixture.capture.blobs);
        }
    }

    @Test void actualCharBufferPathWithReplacementAndOrdinaryEmoji() throws Exception {
        try (var fixture = new Fixture(); var replacementImage = image()) {
            var text = new icyllis.modernui.text.SpannableString("x🎵");
            text.setSpan(new icyllis.modernui.text.style.ImageSpan(replacementImage), 0, 1,
                    icyllis.modernui.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            fixture.draw(fixture.layout(text, false, false, 0));
            assertEquals(2, fixture.capture.images.size(), "replacement image and normal emoji in TextLine's cached char buffer each draw once");
            assertEquals(0, fixture.capture.blobs);
        }
    }

    @Test void actualStaticTextUtilsOverloadsPreserveContextAndValidateRanges() throws Exception {
        try (var fixture = new Fixture()) {
            var paint = new icyllis.modernui.text.TextPaint();
            paint.setTypeface(icyllis.modernui.text.Typeface.createTypeface(new FontFamily(fixture.font), FontFamily.SANS_SERIF));
            paint.setTextSize(17);
            Class<?> helpers = fixture.loader.loadClass(TEXT + "TextUtils");
            for (Class<?> inputType : new Class<?>[]{char[].class, CharSequence.class}) {
                var method = helpers.getMethod("drawTextRun", Canvas.class, inputType, int.class, int.class,
                        int.class, int.class, float.class, float.class, boolean.class, icyllis.modernui.text.TextPaint.class);
                String context = "x🎵👩‍🎤y";
                Object input = inputType == char[].class ? context.toCharArray() : context;
                for (boolean rtl : new boolean[]{false, true}) {
                    fixture.capture.reset();
                    method.invoke(null, fixture.canvas, input, 1, context.length() - 1, 0, context.length(), 10f, 40f, rtl, paint);
                    assertEquals(2, fixture.capture.images.size(), "actual context slice keeps ZWJ and one image pass");
                    assertEquals(0, fixture.capture.blobs, "outside outline context is not drawn");
                }
                var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null,
                        fixture.canvas, input, 0, context.length() + 1, 0, context.length(), 0f, 0f, false, paint));
                assertInstanceOf(IndexOutOfBoundsException.class, failure.getCause());
            }
        }
    }

    @Test void productionMixinTargetsActualCoreSitesAndSharesOnlyInvocationLocalState() throws Exception {
        for (boolean utilities : new boolean[]{false, true}) {
        String targetClass = utilities ? "TextUtils" : "TextLine";
        String path = "indi/mopelotus/musichud/mixin/ModernUiEmoji" + targetClass + "Mixin.class";
        var node = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "production mixin class must be compiled");
            new ClassReader(input).accept(node, 0);
        }
        assertTrue(node.fields.isEmpty(), "mixin must not keep drawing state on pooled TextLine objects");
        var mixin = annotations(node.visibleAnnotations, node.invisibleAnnotations).stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        assertEquals(List.of("icyllis.modernui.text." + targetClass), value(mixin, "targets"));
        assertEquals(false, value(mixin, "remap"));
        int wrappers = 0;
        for (var method : node.methods) {
            for (var annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (!annotation.desc.endsWith("/WrapOperation;")) continue;
                wrappers++;
                assertEquals(List.of(utilities ? "drawTextRun" : "handleText"), value(annotation, "method"));
                Object atValue = value(annotation, "at");
                var at = (AnnotationNode) (atValue instanceof List<?> list ? list.getFirst() : atValue);
                String target = (String) value(at, "target");
                int expected = utilities || target.contains("doLayoutRun") ? 2 : 1;
                if (utilities) assertTrue((method.access & Opcodes.ACC_STATIC) != 0, "static host requires static wrapper");
                assertEquals(expected, value(annotation, "require"));
                assertEquals(expected, value(annotation, "allow"));
                assertTrue(target.equals("Licyllis/modernui/graphics/text/ShapedText;doLayoutRun([CIIIIZLicyllis/modernui/graphics/text/FontPaint;Licyllis/modernui/graphics/text/FontMetricsInt;" + CONSUMER + ")F") ||
                        target.equals("Licyllis/modernui/graphics/Canvas;drawTextBlob(Licyllis/arc3d/sketch/TextBlob;FFLicyllis/modernui/graphics/Paint;)V"));
                boolean shared = false;
                for (var parameterAnnotations : new List[][] {method.visibleParameterAnnotations, method.invisibleParameterAnnotations}) {
                    if (parameterAnnotations == null) continue;
                    for (var parameter : parameterAnnotations) {
                        if (parameter == null) continue;
                        for (Object raw : parameter) {
                            var a = (AnnotationNode) raw;
                            if (a.desc.endsWith("/Share;")) {
                                assertEquals("musichud_tuneweave$emojiRuns", value(a, "value"));
                                shared = true;
                            }
                        }
                    }
                }
                assertTrue(shared, "capture and flush must use the same method-local Share");
            }
        }
        assertEquals(2, wrappers);
        // Independently validate the target's two shaping calls and one draw call against real bytecode.
        try (var input = getClass().getClassLoader().getResourceAsStream("icyllis/modernui/text/" + targetClass + ".class")) {
            assertNotNull(input);
            weave(input.readAllBytes());
        }
        }
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> first, List<AnnotationNode> second) {
        var all = new ArrayList<AnnotationNode>();
        if (first != null) all.addAll(first);
        if (second != null) all.addAll(second);
        return all;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    /** Called only by the explicit bytecode fixture; no thread/global drawing state is used. */
    public static ShapedText.RunConsumer collect(ShapedText.RunConsumer original,
                                                  List<EmojiAwareCanvas.EmojiRun> runs) {
        return EmojiAwareCanvas.collectEmojiRuns(original, runs == null ? null : runs::add);
    }
    public static void flush(Canvas canvas, TextBlob blob, float x, float baseline, Paint paint,
                             List<EmojiAwareCanvas.EmojiRun> runs) {
        canvas.drawTextBlob(blob, x, baseline, paint);
        if (canvas instanceof EmojiAwareCanvas emoji && runs != null) emoji.drawEmojiRuns(runs, x, baseline, paint);
    }

    private static byte[] weave(byte[] original) {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        boolean utilities = node.name.endsWith("/TextUtils");
        int layouts = 0, blobs = 0;
        for (var method : node.methods) {
            if (!method.name.equals(utilities ? "drawTextRun" : "handleText")) continue;
            int slot = method.maxLocals++;
            var init = new InsnList();
            var measured = new LabelNode();
            var done = new LabelNode();
            init.add(new VarInsnNode(Opcodes.ALOAD, utilities ? 0 : 7)); // actual static helper/handleText Canvas argument
            init.add(new JumpInsnNode(Opcodes.IFNULL, measured));
            init.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            init.add(new InsnNode(Opcodes.DUP));
            init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
            init.add(new JumpInsnNode(Opcodes.GOTO, done));
            init.add(measured);
            init.add(new InsnNode(Opcodes.ACONST_NULL));
            init.add(done);
            init.add(new VarInsnNode(Opcodes.ASTORE, slot));
            method.instructions.insert(init);
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("icyllis/modernui/graphics/text/ShapedText") && call.name.equals("doLayoutRun")) {
                    assertEquals("([CIIIIZLicyllis/modernui/graphics/text/FontPaint;Licyllis/modernui/graphics/text/FontMetricsInt;" + CONSUMER + ")F", call.desc);
                    var wrap = new InsnList();
                    wrap.add(new VarInsnNode(Opcodes.ALOAD, slot));
                    wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "collect", "(" + CONSUMER + LIST + ")" + CONSUMER, false));
                    method.instructions.insertBefore(call, wrap);
                    layouts++;
                } else if (call.owner.equals("icyllis/modernui/graphics/Canvas") && call.name.equals("drawTextBlob")) {
                    assertEquals("(Licyllis/arc3d/sketch/TextBlob;FFLicyllis/modernui/graphics/Paint;)V", call.desc);
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, slot));
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = SELF;
                    call.name = "flush";
                    call.desc = "(Licyllis/modernui/graphics/Canvas;Licyllis/arc3d/sketch/TextBlob;FFLicyllis/modernui/graphics/Paint;" + LIST + ")V";
                    blobs++;
                }
            }
        }
        assertEquals(2, layouts, "both real TextLine char-buffer paths must be covered");
        assertEquals(utilities ? 2 : 1, blobs, "actual TextUtils/TextLine blob site contract");
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Named copies share the actual core package/loader, preserving package-private core APIs. */
    private static final class CoreLoader {
        private static Map<String, Class<?>> copies;
        synchronized Class<?> loadClass(String name) throws Exception {
            if (copies == null) {
                var mapping = new LinkedHashMap<String, String>();
                for (String suffix : List.of("TextUtils", "TextLine", "Layout", "StaticLayout", "StaticLayout$Builder")) {
                    String oldName = (TEXT + suffix).replace('.', '/');
                    mapping.put(oldName, oldName.replace("/text/", "/text/EmojiFixture"));
                }
                var lookup = MethodHandles.privateLookupIn(icyllis.modernui.text.Layout.class, MethodHandles.lookup());
                copies = new HashMap<>();
                for (String originalName : mapping.keySet()) {
                    try (var input = TextLineEmojiTest.class.getClassLoader().getResourceAsStream(originalName + ".class")) {
                        if (input == null) throw new ClassNotFoundException(originalName);
                        byte[] bytes = input.readAllBytes();
                        if (originalName.endsWith("/TextLine") || originalName.endsWith("/TextUtils")) bytes = weave(bytes);
                        var writer = new ClassWriter(0);
                        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(mapping)), 0);
                        Class<?> copy = lookup.defineClass(writer.toByteArray());
                        copies.put(originalName.replace('/', '.'), copy);
                    }
                }
            }
            return copies.containsKey(name) ? copies.get(name) : Class.forName(name);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CoreLoader loader = new CoreLoader();
        final EmojiFont font = font();
        final AtomicInteger loads = new AtomicInteger();
        final EmojiImages images = new EmojiImages(() -> new EmojiImages.Generation(1, font), name -> {
            loads.incrementAndGet();
            return image();
        });
        final Capture capture = new Capture();
        final EmojiAwareCanvas canvas = new EmojiAwareCanvas(capture, images);
        Object layout(CharSequence value, boolean rtl, boolean decorated, int shift) throws Exception {
            Class<?> paintType = loader.loadClass(TEXT + "TextPaint");
            Object paint = paintType.getConstructor().newInstance();
            Class<?> typefaceType = loader.loadClass(TEXT + "Typeface");
            Object typeface = typefaceType.getMethod("createTypeface", FontFamily[].class).invoke(null,
                    (Object) new FontFamily[]{new FontFamily(font), FontFamily.SANS_SERIF});
            paintType.getMethod("setTypeface", typefaceType).invoke(paint, typeface);
            paintType.getMethod("setTextSize", float.class).invoke(paint, 17f);
            if (shift != 0 || decorated) {
                var styled = new icyllis.modernui.text.SpannableString(value);
                styled.setSpan(new icyllis.modernui.text.style.CharacterStyle() {
                    @Override public void updateDrawState(icyllis.modernui.text.TextPaint target) {
                        target.baselineShift = shift;
                        if (decorated) {
                            target.bgColor = 0xff123456;
                            target.setUnderline(true);
                            target.setStrikethrough(true);
                        }
                    }
                }, 0, value.length(), icyllis.modernui.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value = styled;
            }
            Class<?> layoutType = loader.loadClass(TEXT + "StaticLayout");
            Object builder = layoutType.getMethod("builder", CharSequence.class, int.class, int.class, paintType, int.class)
                    .invoke(null, value, 0, value.length(), paint, 300);
            if (rtl) {
                Class<?> heuristic = loader.loadClass(TEXT + "TextDirectionHeuristic");
                Object direction = loader.loadClass(TEXT + "TextDirectionHeuristics").getField("RTL").get(null);
                builder.getClass().getMethod("setTextDirection", heuristic).invoke(builder, direction);
            }
            return builder.getClass().getMethod("build").invoke(builder);
        }
        void draw(Object layout) throws Exception { layout.getClass().getMethod("draw", Canvas.class).invoke(layout, canvas); }
        public void close() { images.close(); capture.close(); }
    }

    private static EmojiFont font() {
        var coverage = new IntOpenHashSet();
        var map = new Object2IntOpenHashMap<CharSequence>();
        String[] sequences = {"🎵", "👩‍🎤"};
        for (int i = 0; i < sequences.length; i++) {
            map.put(sequences[i], i + 1);
            sequences[i].codePoints().forEach(coverage::add);
        }
        return new EmojiFont("fixture emoji", coverage, 72, 56, 4, 64, map, List.of("note.png", "singer.png"));
    }
    private static FontPaint fontPaint(EmojiFont font) {
        var paint = new FontPaint();
        paint.setFont(new FontCollection(new FontFamily(font), FontFamily.SANS_SERIF));
        paint.setFontSize(17);
        paint.setLocale(Locale.ROOT);
        return paint;
    }
    private static Image image() {
        try {
            var constructor = Image.class.getDeclaredConstructor(icyllis.arc3d.sketch.Image.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new TestImage());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class TestImage extends icyllis.arc3d.sketch.Image {
        TestImage() { super(ImageInfo.make(72, 72, ColorInfo.CT_RGBA_8888, ColorInfo.AT_PREMUL, null)); }
        @Override protected void deallocate() {}
    }
    private static final class Capture extends icyllis.arc3d.sketch.Canvas {
        final List<float[]> images = new ArrayList<>();
        int blobs, rects;
        Runnable onImage;
        Capture() { super(1000, 1000); }
        void reset() { images.clear(); blobs = rects = 0; }
        @Override protected void onDrawImageRect(icyllis.arc3d.sketch.Image image, Rect2fc src, Rect2fc dst,
                SamplingOptions sampling, icyllis.arc3d.sketch.Paint paint, int constraint) {
            images.add(new float[]{dst.left(), dst.top(), dst.right(), dst.bottom()});
            if (onImage != null) onImage.run();
        }
        @Override protected void onDrawRect(Rect2fc rect, icyllis.arc3d.sketch.Paint paint) { rects++; }
        @Override protected void onDrawTextBlob(TextBlob blob, float x, float y, icyllis.arc3d.sketch.Paint paint) { if (blob != null) blobs++; }
    }
}
