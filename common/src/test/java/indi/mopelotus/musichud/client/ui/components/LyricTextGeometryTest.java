package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.graphics.text.FontFamily;
import icyllis.modernui.text.*;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.client.utils.ui.GraphemeProgress;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricTextGeometryTest {
    @Test void actualEmojiLayoutSweepsAcrossTheWholeClusterWithOrWithoutSpan() {
        for (String text : List.of("👩‍🎤", "☀️", "é", "😀")) {
            for (boolean span : List.of(false, true)) {
                var layout = layout(text, span, 1000);
                var boundaries = new GraphemeProgress(text);
                float from = position(layout, boundaries, 0), to = position(layout, boundaries, text.length());
                assertTrue(to > from, text);
                for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                    assertEquals(from + (to - from) * fraction,
                            position(layout, boundaries, text.length() * fraction), .001f, text);
                }
            }
        }
    }

    @Test void asciiAndMixedEmojiReplacementWordsKeepInternalProgressAndEnd() {
        for (String text : List.of("Matrix", "A😀B", "prefix A👩‍🎤B suffix")) {
            var layout = layout(text, true, 1000);
            var progress = new GraphemeProgress(text);
            float previous = Float.NEGATIVE_INFINITY;
            for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
                float current = position(layout, progress, text.length() * fraction);
                assertTrue(current > previous, text);
                previous = current;
            }
            assertEquals(layout.getPrimaryHorizontal(text.length()), previous, .001f);
            if (text.equals("Matrix")) {
                assertEquals(layout.getPrimaryHorizontal(1), position(layout, progress, 1), .001f); // Preserve ordinary ASCII caret geometry.
                assertTrue(position(layout, progress, 1) < previous);
            }
        }
    }

    @Test void wrappedWordsUseTheirOwnLineAnchorsAndRetainLineEnds() {
        String text = "word Matrix tail";
        var layout = layout(text, true, 120);
        assertEquals(3, layout.getLineCount());
        var progress = new GraphemeProgress(text);
        var spanned = (Spanned) layout.getText();
        float[] starts = lineStarts(layout);
        for (var span : spanned.getSpans(0, text.length(), LyricLine.HighlightSpan.class)) {
            int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
            int line = layout.getLineForOffset(start);
            assertEquals(starts[line] + layout.getPrimaryHorizontal(start), position(layout, progress, start), .001f);
            assertEquals(starts[line] + layout.getPrimaryHorizontal(end), position(layout, progress, end), .001f);
        }
    }

    @Test void adjacentPhraseBoundariesAtTheNextLineNeverUseThatLinesCaretAsLeftAnchor() {
        for (String text : List.of("aaaabbbbcccc", "AAAA BBBBCCCC")) {
            for (var alignment : List.of(Layout.Alignment.ALIGN_NORMAL, Layout.Alignment.ALIGN_CENTER)) {
                var layout = phraseLayout(text, text.startsWith("aaaa") ? 160 : 200, alignment, false);
                var progress = new GraphemeProgress(text);
                var spanned = (Spanned) layout.getText();
                boolean crossed = false;
                for (var span : spanned.getSpans(0, text.length(), LyricLine.HighlightSpan.class)) {
                    int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
                    if (layout.getLineForOffset(start) == layout.getLineForOffset(end)) continue;
                    crossed = true;
                    float expected = lineStarts(layout)[layout.getLineForOffset(start)] + layout.getPrimaryHorizontal(start);
                    assertEquals(expected, position(layout, progress, start), .001f, text);
                    assertTrue(position(layout, progress, start + (end - start) * .5f) > expected, text);
                }
                assertTrue(crossed, "The regression fixture must end a span at the next line");
                assertEquals(lineStarts(layout)[layout.getLineCount() - 1] + layout.getPrimaryHorizontal(text.length()),
                        position(layout, progress, text.length()), .001f);
            }
        }
    }

    @Test void explicitlyRtlWrappedRunUsesItsOwnerLineRectangle() {
        String text = "אבגדדהוזזחטט";
        var layout = phraseLayout(text, 160, Layout.Alignment.ALIGN_NORMAL, true);
        var progress = new GraphemeProgress(text);
        var spanned = (Spanned) layout.getText();
        boolean crossed = false;
        for (var span : spanned.getSpans(0, text.length(), LyricLine.HighlightSpan.class)) {
            int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
            if (layout.getLineForOffset(start) == layout.getLineForOffset(end)) continue;
            crossed = true;
            assertTrue(layout.isRtlCharAt(start));
            float expected = lineStarts(layout)[layout.getLineForOffset(start)] + layout.getPrimaryHorizontal(start);
            assertEquals(expected, position(layout, progress, start), .001f);
            assertTrue(position(layout, progress, start + (end - start) * .5f) < expected);
        }
        assertTrue(crossed);
    }

    private static Layout phraseLayout(String text, int width, Layout.Alignment alignment, boolean rtl) {
        var line = LyricLine.builder().text(text).startTime(Duration.ZERO).wordByWord(true).build();
        for (int end = 4; end < text.length(); end += 4) {
            line.getPhraseEndingOffsetMap().put(Duration.ofSeconds(end), end);
        }
        line.getPhraseEndingOffsetMap().put(Duration.ofSeconds(text.length()), text.length());
        line.parsePhrases();
        var source = line.getSpannableString();
        return StaticLayout.builder(source, 0, source.length(), paint(), width).setAlignment(alignment)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.FIRSTSTRONG_LTR).build();
    }

    @Test void rtlLocalGeometryRunsBackwardsWithoutApplyingDrawAnimationOffsets() {
        var layout = layout("אבג", true, 1000);
        var spanned = (Spanned) layout.getText();
        var span = spanned.getSpans(0, 3, LyricLine.HighlightSpan.class).getFirst();
        float from = span.horizontalAt(layout.getPaint(), spanned, 0, 3, 0);
        float to = span.horizontalAt(layout.getPaint(), spanned, 0, 3, 3);
        assertTrue(from > to);
        for (int i = 0; i < span.getCharCount(); i++) span.setCharState(i, 500, 2);
        assertEquals(from, span.horizontalAt(layout.getPaint(), spanned, 0, 3, 0));
        assertEquals(to, span.horizontalAt(layout.getPaint(), spanned, 0, 3, 3));
        var progress = new GraphemeProgress(spanned);
        float previous = Float.POSITIVE_INFINITY;
        for (float f : new float[]{0, .25f, .5f, .75f, 1}) {
            float x = position(layout, progress, 3 * f);
            assertTrue(x < previous);
            previous = x;
        }
    }

    private static float position(Layout layout, GraphemeProgress progress, float offset) {
        return LyricTextGeometry.logicalXAt(layout, lineStarts(layout), offset, progress);
    }

    private static float[] lineStarts(Layout layout) {
        float[] starts = new float[layout.getLineCount()];
        for (int i = 1; i < starts.length; i++) starts[i] = starts[i - 1] + layout.getLineWidth(i - 1);
        return starts;
    }

    /** Real upstream EmojiFont layout, no image decode, device or GPU rendering. */
    private static TextPaint paint() {
        var map = new Object2IntOpenHashMap<CharSequence>();
        map.put("👩‍🎤", 1); map.put("☀️", 2); map.put("😀", 3);
        var emoji = new EmojiFont("CPU emoji layout", new IntOpenHashSet(new int[]{0x1f469, 0x1f3a4, 0x2600, 0x1f600}),
                72, 56, 4, 64, map, List.of("1f469_200d_1f3a4.png", "2600.png", "1f600.png"));
        var families = new ArrayList<FontFamily>();
        families.add(new FontFamily(emoji)); families.addAll(Typeface.SERIF.getFamilies());
        var paint = new TextPaint();
        paint.setTypeface(Typeface.createTypeface(families.toArray(FontFamily[]::new)));
        paint.setTextSize(32);
        return paint;
    }

    private static Layout layout(String text, boolean span, int width) {
        var paint = paint();
        CharSequence source = text;
        if (span) {
            var line = LyricLine.builder().text(text).startTime(Duration.ZERO).wordByWord(true).build();
            line.getPhraseEndingOffsetMap().put(Duration.ofSeconds(4), text.length());
            line.parsePhrases(); source = line.getSpannableString();
        }
        return StaticLayout.builder(source, 0, source.length(), paint, width).build();
    }
}
