package indi.mopelotus.musichud.client.utils.ui;

/** A lyric row's position and release progress; both survive rapid highlight retargeting. */
public class LyricRowMotion {
    private final SpringValue spring = new SpringValue(0.6f, 1f);
    private final SpringValue progress = new SpringValue(0.6f, 1f);
    public void retarget(float target, long nowNanos) {
            spring.setTarget(target, nowNanos);
        }

    public void retargetProgress(float target, long nowNanos) {
            progress.setTarget(target, nowNanos);
        }

        /**
         * Snap the release factor. Safe at a wave start because the compensation has just been
         * rebased to zero, so the factor does not affect the rendered value or its velocity.
         */
    public void snapProgress(float value) {
            progress.jumpTo(value);
        }

        /** Shift the running base spring by {@code delta} without interrupting it (keeps velocity). */
    public void translate(float delta) {
            spring.translate(delta);
        }

    public void update(long nowNanos) {
            spring.update(nowNanos);
            progress.update(nowNanos);
        }

    public boolean isSettled() {
            return spring.isSettled() && progress.isSettled();
        }

    public void jumpTo(float value) {
            spring.jumpTo(value);
            progress.jumpTo(1f);
        }

    public float getValue() {
            return spring.getValue();
        }

    public float getTarget() {
            return spring.getTarget();
        }

    public float getProgress() {
            return progress.getValue();
        }
}
