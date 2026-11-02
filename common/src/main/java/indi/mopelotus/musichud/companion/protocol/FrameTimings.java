package indi.mopelotus.musichud.companion.protocol;

import java.util.Arrays;

/** Bounded diagnostics, owned by the caller's render thread. Values are milliseconds. */
public final class FrameTimings {
    private final double[] samples = new double[600];
    private int count, cursor;

    public void add(double milliseconds) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) return;
        samples[cursor] = milliseconds;
        cursor = (cursor + 1) % samples.length;
        count = Math.min(count + 1, samples.length);
    }

    public double percentile(double fraction) {
        if (count == 0) return 0;
        if (!(fraction > 0 && fraction <= 1)) throw new IllegalArgumentException("Invalid percentile");
        double[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(fraction * count) - 1];
    }

    public void reset() { count = cursor = 0; }
}
