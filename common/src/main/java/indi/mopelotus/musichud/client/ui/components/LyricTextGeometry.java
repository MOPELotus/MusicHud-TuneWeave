package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.text.Layout;
import icyllis.modernui.text.Spanned;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.client.utils.ui.GraphemeProgress;

/** Layout-space sweep coordinates, before the lyric's raise/scale drawing animation. */
final class LyricTextGeometry {
    private LyricTextGeometry() {}

    static float logicalXAt(Layout layout, float[] lineStarts, float offset, GraphemeProgress progress) {
        CharSequence text = layout.getText();
        float position = Float.isNaN(offset) ? 0 : Math.clamp(offset, 0, text.length());
        int point = (int) Math.floor(position);
        if (text instanceof Spanned spanned) {
            for (var span : spanned.getSpans(point, Math.min(text.length(), point + 1), LyricLine.HighlightSpan.class)) {
                int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
                if (start <= position && (position < end || position == text.length() && position == end)) {
                    int line = layout.getLineForOffset(start);
                    float from = layout.getPrimaryHorizontal(start);
                    float left;
                    if (layout.getLineForOffset(end) != line) {
                        // A boundary at the next line belongs to that line's caret. Use
                        // this run's direction and measured rectangle on the owner line.
                        float width = span.getSize(layout.getPaint(), text, start, end, null);
                        left = layout.isRtlCharAt(start) ? from - width : from;
                    } else {
                        left = Math.min(from, layout.getPrimaryHorizontal(end));
                    }
                    return lineStarts[line] + left
                            + span.horizontalAt(layout.getPaint(), text, start, end, position);
                }
            }
        }
        return progress.interpolate(position, boundary ->
                lineStarts[layout.getLineForOffset(boundary)] + layout.getPrimaryHorizontal(boundary));
    }
}
