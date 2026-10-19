package indi.mopelotus.musichud.client.utils.ui;

import com.ibm.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/** One immutable boundary index per lyric text; offsets retain the provider's UTF-16 timing. */
public final class GraphemeProgress {
    private final int[] boundaries;

    public GraphemeProgress(CharSequence text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text.toString());
        var values = new ArrayList<Integer>();
        for (int i = iterator.first(); i != BreakIterator.DONE; i = iterator.next()) values.add(i);
        boundaries = values.stream().mapToInt(Integer::intValue).toArray();
    }

    public record Interval(int start, int end, float fraction) {}

    public Interval at(float offset) {
        int length = boundaries[boundaries.length - 1];
        float position = Float.isNaN(offset) ? 0 : Math.clamp(offset, 0, length);
        if (position == length) return new Interval(length, length, 0);
        int index = Arrays.binarySearch(boundaries, (int) Math.floor(position));
        if (index < 0) index = -index - 2;
        int from = boundaries[index], to = boundaries[index + 1];
        return new Interval(from, to, (position - from) / (to - from));
    }

    public float interpolate(float offset, IntToDoubleFunction boundaryX) {
        Interval interval = at(offset);
        float from = (float) boundaryX.applyAsDouble(interval.start());
        if (interval.start() == interval.end()) return from;
        float to = (float) boundaryX.applyAsDouble(interval.end());
        return from + interval.fraction() * (to - from);
    }
}
