package indi.mopelotus.musichud.client.utils.ui;

/** Phrase motion uses playback-relative time so independent lines do not share animation state. */
public final class LyricMotion {
    private static final long DURATION_MILLIS = 1000;
    private static final SpringInterpolator SPRING = new SpringInterpolator(1, 1);

    private LyricMotion() {}

    public static float lowerOffset(float height, long elapsedMillis) {
        if (elapsedMillis >= DURATION_MILLIS) return 0;
        float progress = Math.clamp((float) elapsedMillis / DURATION_MILLIS, 0, 1);
        return -height * (1 - SPRING.getInterpolation(progress));
    }
}
