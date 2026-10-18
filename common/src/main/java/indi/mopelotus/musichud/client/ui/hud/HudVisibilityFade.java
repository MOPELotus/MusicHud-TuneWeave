package indi.mopelotus.musichud.client.ui.hud;

/** Render-thread opacity state. Retargeting preserves the opacity at that instant. */
final class HudVisibilityFade {
    private static final long DURATION_NANOS = 300_000_000L;
    private float alpha;
    private float from;
    private float target;
    private long started;
    private boolean moving;

    static float target(boolean enabled, boolean hudEnabled, boolean preview, boolean autoHide, boolean idle) {
        return enabled && hudEnabled && (preview || !autoHide || !idle) ? 1f : 0f;
    }

    void snap(float target) {
        alpha = from = this.target = target;
        moving = false;
    }

    float advance(float nextTarget, long now) {
        sample(now);
        if (nextTarget != target) {
            from = alpha;
            target = nextTarget;
            started = now;
            moving = true;
        }
        return alpha;
    }

    private void sample(long now) {
        if (!moving) return;
        double progress = Math.max(0d, Math.min(1d, (double) (now - started) / DURATION_NANOS));
        float eased = (float) ((1d - Math.cos(Math.PI * progress)) / 2d);
        alpha = from + (target - from) * eased;
        if (progress >= 1d) {
            alpha = target;
            moving = false;
        }
    }

    boolean hidden() { return !moving && alpha == 0f; }
}
