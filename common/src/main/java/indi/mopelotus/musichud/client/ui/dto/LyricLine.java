package indi.mopelotus.musichud.client.ui.dto;

import com.ibm.icu.text.BreakIterator;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.text.FontMetricsInt;
import icyllis.modernui.graphics.text.ShapedText;
import icyllis.modernui.text.*;
import icyllis.modernui.text.style.ReplacementSpan;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Builder
public class LyricLine implements Comparable<LyricLine> {
    public static final int DURABLE_PHRASE_MILLIS = 1000;
    public static final int FULL_DURABLE_PHRASE_MILLIS = 1200;
    @Builder.Default
    Type type = Type.NORMAL;
    Duration startTime;
    Duration duration;
    String text;
    String translatedText;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    LyricLine previous;
    @Setter
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    LyricLine next;
    Map<Duration, Integer> phraseEndingOffsetMap;
    Map<Duration, Phrase> phraseEndDurationMap;
    List<Phrase> phrases;
    @Builder.Default
    boolean wordByWord = false;
    SpannableString spannableString;
    @Builder.Default
    private boolean phraseParsed = false;

    private static HighlightSpan applySpan(SpannableString spannableString, int start, int end, int charStartInPhrase) {
        HighlightSpan span = new HighlightSpan(charStartInPhrase, Character.codePointCount(spannableString, start, end));
        spannableString.setSpan(
                span,
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return span;
    }

    public Map<Duration, Integer> getPhraseEndingOffsetMap() {
        if (phraseEndingOffsetMap == null) {
            phraseEndingOffsetMap = new LinkedHashMap<>(0);
        }
        return phraseEndingOffsetMap;
    }

    @Override
    public int compareTo(@NotNull LyricLine o) {
        return startTime.compareTo(o.startTime);
    }

    public boolean isAfter(@NotNull LyricLine o) {
        return compareTo(o) > 0;
    }

    public String getTranslatedText() {
        return Objects.requireNonNullElse(translatedText, "");
    }

    public String getText() {
        return Objects.requireNonNullElse(text, "");
    }

    public Duration getStartTime() {
        return Objects.requireNonNullElse(startTime, Duration.ZERO);
    }

    public void parsePhrases() {
        if (!phraseParsed) {
            forceParsePhrases();
        }
    }

    public void forceParsePhrases() {
        phraseParsed = true;
        spannableString = new SpannableString(getText());
        phrases = new ArrayList<>();
        phraseEndDurationMap = new LinkedHashMap<>();
        if (wordByWord && phraseEndingOffsetMap != null) {
            int size = phraseEndingOffsetMap.size();
            phraseEndDurationMap = new LinkedHashMap<>(size);
            phrases = new ArrayList<>(size);
            int lastPhraseEnd = 0;
            Duration lastEndTime = getStartTime();
            int textLength = spannableString.length();
            BreakIterator graphemes = LyricGraphemes.boundaries(spannableString);
            //LinkedHashMap
            for (Map.Entry<Duration, Integer> entry : phraseEndingOffsetMap.entrySet()) {
                Integer phraseEnd = entry.getValue();
                Duration endTime = entry.getKey();
                // Ignore malformed timing/offset entries without poisoning the next valid phrase.
                if (phraseEnd == null || endTime == null || phraseEnd <= lastPhraseEnd
                        || phraseEnd > textLength || endTime.compareTo(lastEndTime) <= 0) continue;
                // A platform offset must not split a surrogate, combining sequence, VS or ZWJ emoji.
                if (!graphemes.isBoundary(phraseEnd)) continue;
                List<HighlightSpan> spans = new ArrayList<>(1);
                Duration phraseDuration = endTime.minus(lastEndTime);
                int durationMillis = phraseDuration.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) >= 0
                        ? Integer.MAX_VALUE : (int) phraseDuration.toMillis();

                if (durationMillis > DURABLE_PHRASE_MILLIS) {
                    // Split the phrase into word-level spans: each word becomes one atomic
                    // replacement run for the line breaker, so wrapping never happens
                    // mid-word; per-char animation is handled inside the span's draw.
                    // Whitespace is left unspanned to preserve word-boundary break points.
                    int spanEnd = Math.min(phraseEnd, textLength);
                    int wordStart = -1;
                    for (int i = lastPhraseEnd; i <= spanEnd;
                         i = i == spanEnd ? spanEnd + 1 : graphemes.following(i)) {
                        boolean boundary = i >= spanEnd
                                || Character.isWhitespace(Character.codePointAt(spannableString, i));
                        if (!boundary && wordStart < 0) {
                            wordStart = i;
                        } else if (boundary && wordStart >= 0) {
                            spans.add(applySpan(spannableString, wordStart, i,
                                    Character.codePointCount(spannableString, lastPhraseEnd, wordStart)));
                            wordStart = -1;
                        }
                    }
                } else {
                    if (phraseEnd <= textLength) {
                        spans.add(applySpan(spannableString, lastPhraseEnd, phraseEnd, 0));
                    }
                }
                lastPhraseEnd = phraseEnd;
                Phrase phrase = new Phrase(entry.getValue(), endTime, durationMillis, spans);
                phraseEndDurationMap.put(endTime, phrase);
                phrases.add(phrase);
                lastEndTime = endTime;
            }
        }
        if (phrases.isEmpty()) wordByWord = false;
    }

    // 二分查找第一个结束时间 > playedDuration 的短语索引
    public int binarySearchPhraseIndex(Duration playedDuration) {
        List<Phrase> phrases = getPhrases();
        int low = 0, high = phrases.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (phrases.get(mid).endTime().compareTo(playedDuration) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low; // 返回第一个大于 playedDuration 的索引，可能等于 size()
    }

    public enum Type {
        NORMAL, META_DATA, RHYTHM
    }

    public record Phrase(int endOffset, Duration endTime, int durationMillis, List<HighlightSpan> spans) {

        public int charCount() {
            int total = 0;
            for (HighlightSpan span : spans) {
                total += span.getCharCount();
            }
            return total;
        }
    }

    @ToString
    public static class HighlightSpan extends ReplacementSpan {
        // Index of the first char (code point) of this span within its phrase, used by the
        // view to compute per-char stagger delays for the karaoke raise animation.
        @Getter
        private final int charStartInPhrase;
        // Per-char (code point) animation state
        private final float[] charYOffsets;
        private final float[] charScales;
        // Shape whole grapheme clusters in span context. The first original code point
        // owns each cluster's animation state, preserving the platform timing indices.
        // Drawing order follows bidi visual order; paint/shader remains caller-owned.
        private ShapedText[] clusterGlyphs;
        private int[] clusterWidths;
        private List<LyricGraphemes.Cluster> clusters;
        private int cacheStart;
        private int cacheEnd;
        private icyllis.modernui.graphics.text.FontPaint cachedPaint;
        private CharSequence cachedText;

        private HighlightSpan(int charStartInPhrase, int charCount) {
            this.charStartInPhrase = charStartInPhrase;
            this.charYOffsets = new float[charCount];
            this.charScales = new float[charCount];
            Arrays.fill(charScales, 1f);
        }

        public int getCharCount() {
            return charYOffsets.length;
        }

        // Applies the same offset to every char of this span
        public void setYOffset(float yOffset) {
            Arrays.fill(charYOffsets, yOffset);
        }

        // Applies the same scale to every char of this span
        public void setScale(float scale) {
            Arrays.fill(charScales, scale);
        }

        public void setCharState(int index, float yOffset, float scale) {
            charYOffsets[index] = yOffset;
            charScales[index] = scale;
        }

        private void ensureShaped(@NonNull TextPaint paint, CharSequence text,
                                  int start, int end) {
            Objects.checkFromToIndex(start, end, text.length());
            // Spanned subsequences copy this span and rebase its bounds. Always ask the
            // current owner, including when its first measurement covers only part of us.
            int ownerStart = start;
            int ownerEnd = end;
            if (text instanceof Spanned spanned) {
                int attachedStart = spanned.getSpanStart(this);
                int attachedEnd = spanned.getSpanEnd(this);
                if (attachedStart >= 0 && attachedStart <= start && attachedEnd >= end
                        && attachedEnd <= text.length()) {
                    ownerStart = attachedStart;
                    ownerEnd = attachedEnd;
                }
            }
            if (Character.codePointCount(text, ownerStart, ownerEnd) > charYOffsets.length) {
                throw new IllegalArgumentException("Lyric span exceeds its animation state");
            }
            if (clusterGlyphs != null && cachedText == text && ownerStart == cacheStart && ownerEnd == cacheEnd
                    && cachedPaint.equals(paint.getInternalPaint())) {
                return;
            }
            cachedPaint = paint.createInternalPaint();
            cachedText = text;
            cacheStart = ownerStart;
            cacheEnd = ownerEnd;
            clusters = LyricGraphemes.visualOrder(text, ownerStart, ownerEnd);
            clusterGlyphs = new ShapedText[clusters.size()];
            clusterWidths = new int[clusters.size()];
            for (int j = 0; j < clusters.size(); j++) {
                LyricGraphemes.Cluster cluster = clusters.get(j);
                ShapedText shaped = TextShaper.shapeTextRun(
                        text, cluster.start(), cluster.end() - cluster.start(),
                        ownerStart, ownerEnd - ownerStart, cluster.rtl(), paint);
                clusterGlyphs[j] = shaped;
                clusterWidths[j] = Math.round(shaped.getAdvance());
            }
        }

        /** Layout-space caret inside the drawn clusters, without raise/scale animation. */
        public float horizontalAt(TextPaint paint, CharSequence text, int start, int end, float offset) {
            ensureShaped(paint, text, start, end);
            float position = Float.isNaN(offset) ? cacheStart : Math.clamp(offset, cacheStart, cacheEnd);
            float left = 0;
            for (int i = 0; i < clusters.size(); i++) {
                LyricGraphemes.Cluster cluster = clusters.get(i);
                float width = clusterWidths[i];
                if (position >= cluster.start() && (position < cluster.end()
                        || position == cacheEnd && position == cluster.end())) {
                    float fraction = (position - cluster.start()) / (cluster.end() - cluster.start());
                    return left + (cluster.rtl() ? 1 - fraction : fraction) * width;
                }
                left += width;
            }
            return 0; // Empty span.
        }

        @Override
        public int getSize(@NonNull TextPaint paint, CharSequence text,
                           int start, int end, @Nullable FontMetricsInt fm) {
            ensureShaped(paint, text, start, end);
            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }
            int total = 0;
            for (int j = 0; j < clusterGlyphs.length; j++) {
                ShapedText shaped = clusterGlyphs[j];
                if (shaped == null) {
                    break;
                }
                LyricGraphemes.Cluster cluster = clusters.get(j);
                if (cluster.start() < end && cluster.end() > start) {
                    total += clusterWidths[j];
                }
            }
            return total;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text,
                         int start, int end, float x, int top, int y, int bottom,
                         @NonNull TextPaint paint) {
            ensureShaped(paint, text, start, end);
            float cx = x;
            for (int j = 0; j < clusterGlyphs.length; j++) {
                ShapedText shaped = clusterGlyphs[j];
                if (shaped == null) {
                    break;
                }
                LyricGraphemes.Cluster cluster = clusters.get(j);
                if (cluster.start() < end && cluster.end() > start) {
                    float w = clusterWidths[j];
                    float s = charScales[cluster.codePointIndex()];
                    float yOff = charYOffsets[cluster.codePointIndex()];
                    if (s == 1f) {
                        canvas.drawShapedText(shaped, cx, y + yOff, paint);
                    } else {
                        float pivotX = cx + 0.5f * w;
                        canvas.save();
                        try {
                            canvas.scale(s, s, pivotX, y);
                            canvas.drawShapedText(shaped, cx, y + yOff, paint);
                        } finally {
                            canvas.restore();
                        }
                    }
                    cx += w;
                }
            }
        }
    }
}