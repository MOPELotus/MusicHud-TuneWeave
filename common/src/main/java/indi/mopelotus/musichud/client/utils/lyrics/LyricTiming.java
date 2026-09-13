package indi.mopelotus.musichud.client.utils.lyrics;

import java.time.Duration;

/** Presentation timing uses the lyric timestamp, never the early scroll notification. */
public final class LyricTiming {
    public static final long TRANSLATION_MATCH_TOLERANCE_MILLIS = 80;

    private LyricTiming() {}

    public static float highlightFraction(Duration played, Duration start, long animationMillis) {
        if (animationMillis <= 0) throw new IllegalArgumentException("Invalid animation duration");
        return Math.clamp((float) played.minus(start).toMillis() / animationMillis, 0f, 1f);
    }

    public static long delayMillis(Duration played, Duration start) {
        return Math.max(0, start.minus(played).toMillis());
    }

    /** Closest original-line timestamp within tolerance; QQ LRC translations are often ~10ms off QRC. */
    public static Duration nearest(Duration target, Iterable<Duration> candidates, long toleranceMillis) {
        if (target == null || candidates == null || toleranceMillis < 0) return null;
        Duration best = null;
        long bestDelta = Long.MAX_VALUE;
        long targetMillis = target.toMillis();
        for (Duration candidate : candidates) {
            if (candidate == null) continue;
            long delta = Math.abs(candidate.toMillis() - targetMillis);
            if (delta <= toleranceMillis && delta < bestDelta) {
                best = candidate;
                bestDelta = delta;
            }
        }
        return best;
    }

    public static boolean isPlaceholderTranslation(String text) {
        if (text == null) return true;
        String normalized = text.replace('\u00A0', ' ').replace('\n', ' ').trim();
        return normalized.isEmpty()
                || normalized.equals("//")
                || normalized.equals("/")
                || normalized.equals("…")
                || normalized.equals("...");
    }
}
