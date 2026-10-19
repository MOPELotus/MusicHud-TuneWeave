package indi.mopelotus.musichud.client.ui.dto;

import org.junit.jupiter.api.Test;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.pipeline.ArcCanvas;
import icyllis.modernui.graphics.text.ShapedText;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.text.Typeface;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LyricLineTest {
    @Test void durableEnglishPhrasesKeepWordsTogetherAndLeaveSpacesBreakable() {
        var line = line("hello world", Map.of(Duration.ofSeconds(2), 11));
        line.parsePhrases();
        var spans = line.getPhrases().getFirst().spans();
        assertEquals(2, spans.size());
        assertEquals(10, line.getPhrases().getFirst().charCount());
        assertEquals(0, line.getSpannableString().getSpanStart(spans.get(0)));
        assertEquals(5, line.getSpannableString().getSpanEnd(spans.get(0)));
        assertEquals(6, line.getSpannableString().getSpanStart(spans.get(1)));
        assertEquals(11, line.getSpannableString().getSpanEnd(spans.get(1)));
    }

    @Test void surrogatePairsAreOneAnimatedCharacterAndCannotBeSplitByBadOffsets() {
        var timings = new LinkedHashMap<Duration, Integer>();
        timings.put(Duration.ofSeconds(1), 2); // Inside the emoji's surrogate pair.
        timings.put(Duration.ofSeconds(2), 4);
        var line = line("a😀b", timings);
        line.parsePhrases();
        assertEquals(1, line.getPhrases().size());
        assertEquals(3, line.getPhrases().getFirst().charCount());
        assertEquals(4, line.getPhrases().getFirst().endOffset());
    }

    @Test void invalidOffsetsAndTimesDoNotPoisonLaterValidPhrases() {
        var timings = new LinkedHashMap<Duration, Integer>();
        timings.put(null, 1);
        timings.put(Duration.ofMillis(100), -1);
        timings.put(Duration.ofMillis(200), 500);
        timings.put(Duration.ofMillis(300), null);
        timings.put(Duration.ofSeconds(2), 2);
        timings.put(Duration.ofSeconds(1), 3);
        timings.put(Duration.ofSeconds(4), 4);
        var line = line("abcd", timings);
        assertDoesNotThrow(line::parsePhrases);
        assertEquals(2, line.getPhrases().size());
        assertEquals(1, line.binarySearchPhraseIndex(Duration.ofSeconds(2)));
        assertEquals(2, line.binarySearchPhraseIndex(Duration.ofSeconds(4)));
        assertEquals(2000, line.getPhrases().getLast().durationMillis());
    }

    @Test void emptyAndExtremelyLongPhrasesRemainRenderable() {
        var empty = line(null, Map.of());
        empty.parsePhrases();
        assertFalse(empty.isWordByWord());
        assertEquals("", empty.getSpannableString().toString());
        var longLine = line("ok", Map.of(Duration.ofDays(300), 2));
        assertDoesNotThrow(longLine::parsePhrases);
        assertEquals(Integer.MAX_VALUE, longLine.getPhrases().getFirst().durationMillis());
    }

    @Test void timingOffsetsInsideGraphemeClustersDoNotSplitTheNextValidPhrase() {
        for (String cluster : new String[]{"👩‍🎤", "☀️", "é", "🇨🇳", "👍🏽"}) {
            var timings = new LinkedHashMap<Duration, Integer>();
            timings.put(Duration.ofSeconds(1), Character.charCount(cluster.codePointAt(0)));
            timings.put(Duration.ofSeconds(2), cluster.length());
            var line = line(cluster, timings);
            line.parsePhrases();
            assertEquals(1, line.getPhrases().size(), cluster);
            var phrase = line.getPhrases().getFirst();
            assertEquals(cluster.codePointCount(0, cluster.length()), phrase.charCount());
            assertEquals(cluster.length(), phrase.endOffset());
            assertEquals(0, line.getSpannableString().getSpanStart(phrase.spans().getFirst()));
        }
    }

    @Test void durablePhrasesKeepClusterWordsAndOriginalCodePointTimingCounts() {
        String text = "A👩‍🎤 ☀️é";
        var line = line(text, Map.of(Duration.ofSeconds(2), text.length()));
        line.parsePhrases();
        var phrase = line.getPhrases().getFirst();
        assertEquals(2, phrase.spans().size());
        assertEquals(8, phrase.charCount()); // Space remains breakable, original code points count.
        assertEquals(4, phrase.spans().getFirst().getCharCount());
        assertEquals(5, phrase.spans().getLast().getCharStartInPhrase());
        assertEquals(0, line.getSpannableString().getSpanStart(phrase.spans().getFirst()));
        assertEquals(6, line.getSpannableString().getSpanEnd(phrase.spans().getFirst()));
        assertEquals(7, line.getSpannableString().getSpanStart(phrase.spans().getLast()));
    }

    @Test void copiedSpanUsesRebasedOwnerBoundsAfterPartialMeasurement() {
        String text = "prefix A👩‍🎤B suffix";
        var line = line(text, Map.of(Duration.ofSeconds(3), text.length()));
        line.parsePhrases();
        var original = line.getSpannableString();
        var span = line.getPhrases().getFirst().spans().get(1);
        int start = original.getSpanStart(span), end = original.getSpanEnd(span);
        var paint = paint();
        int originalWidth = span.getSize(paint, original, start, end, null);
        CharSequence copied = original.subSequence(start, end);
        assertInstanceOf(Spanned.class, copied);
        assertEquals(0, ((Spanned) copied).getSpanStart(span));
        assertEquals(copied.length(), ((Spanned) copied).getSpanEnd(span));
        assertDoesNotThrow(() -> span.getSize(paint, copied, 1, copied.length() - 1, null));
        assertEquals(originalWidth, span.getSize(paint, copied, 0, copied.length(), null));
        assertEquals(originalWidth, span.getSize(paint, original, start, end, null));
    }

    @Test void partialBeforeFullDrawKeepsOriginalCodePointAnimationMappingAndPaint() {
        String text = "A👩‍🎤B";
        var line = line(text, Map.of(Duration.ofSeconds(3), text.length()));
        line.parsePhrases();
        var source = line.getSpannableString();
        var span = line.getPhrases().getFirst().spans().getFirst();
        var paint = paint();
        span.getSize(paint, source, 1, text.length() - 1, null);
        for (int i = 0; i < span.getCharCount(); i++) span.setCharState(i, i * 10, 1);
        var canvas = new RecordingCanvas();
        span.draw(canvas, source, 0, source.length(), 0, 0, 20, 100, paint);
        assertEquals(List.of(20f, 30f, 60f), canvas.baselines);
        assertEquals(3, canvas.paints.size()); // A, complete ZWJ cluster, B.
        assertTrue(canvas.paints.stream().allMatch(p -> p == paint));
    }

    @Test void nonSpannedRangesUseTheirOwnBoundsAndRejectIncompatibleLargerText() {
        var line = line("word", Map.of(Duration.ofSeconds(2), 4));
        line.parsePhrases();
        var span = line.getPhrases().getFirst().spans().getFirst();
        var paint = paint();
        int partial = span.getSize(paint, "prefix word suffix", 7, 11, null);
        assertEquals(partial, span.getSize(paint, "word", 0, 4, null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> span.getSize(paint, "word", 0, 5, null));
        assertThrows(IllegalArgumentException.class,
                () -> span.getSize(paint, "longer", 0, 6, null));
    }

    private static TextPaint paint() {
        var paint = new TextPaint();
        paint.setTypeface(Typeface.SERIF);
        paint.setTextSize(32);
        return paint;
    }

    /** Records CPU draw dispatch only: no device, pixels or GPU acceptance is implied. */
    private static final class RecordingCanvas extends ArcCanvas {
        final List<Float> baselines = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();

        RecordingCanvas() { super(null); }

        @Override public void drawShapedText(ShapedText text, float x, float y, Paint paint) {
            baselines.add(y);
            paints.add(paint);
        }
    }

    private static LyricLine line(String text, Map<Duration, Integer> timings) {
        return LyricLine.builder().text(text).startTime(Duration.ZERO).duration(Duration.ofSeconds(4))
                .wordByWord(true).phraseEndingOffsetMap(timings).build();
    }
}
