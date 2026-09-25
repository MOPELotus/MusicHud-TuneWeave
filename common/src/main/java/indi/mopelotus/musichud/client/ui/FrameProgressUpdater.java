package indi.mopelotus.musichud.client.ui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** UI-thread progress loop; queued frames from a previous binding cannot publish or reschedule. */
public final class FrameProgressUpdater {
    private final Consumer<Runnable> nextFrame;
    private final LongSupplier clockMillis;
    private long generation;

    public FrameProgressUpdater(Consumer<Runnable> nextFrame, LongSupplier clockMillis) {
        this.nextFrame = nextFrame;
        this.clockMillis = clockMillis;
    }

    public void stop() { generation++; }

    public void start(BooleanSupplier current, LongSupplier elapsedMillis, long durationMillis,
                      Consumer<Boolean> update) {
        long token = ++generation;
        long started = clockMillis.getAsLong();
        long timeout = Math.max(0, durationMillis) + 120_000L;
        Runnable frame = new Runnable() {
            private long lastSecond = Long.MIN_VALUE;

            @Override public void run() {
                if (generation != token || !current.getAsBoolean()) return;
                long elapsed = Math.max(0, elapsedMillis.getAsLong());
                long second = elapsed / 1000;
                update.accept(second != lastSecond);
                lastSecond = second;
                // update can synchronously stop or replace this binding.
                if (generation == token && current.getAsBoolean()
                        && (durationMillis <= 0 || elapsed < durationMillis)
                        && clockMillis.getAsLong() - started < timeout) {
                    nextFrame.accept(this);
                }
            }
        };
        frame.run(); // Initialize the incoming card before its first rendered frame.
    }
}
