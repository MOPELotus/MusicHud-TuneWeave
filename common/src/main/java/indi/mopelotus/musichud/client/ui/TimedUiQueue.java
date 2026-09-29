package indi.mopelotus.musichud.client.ui;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Ordered delayed presentation with one timer, bounded backlog and cancellation at publication. */
public final class TimedUiQueue<T> {
    private record Entry<T>(T value, long due) {}
    private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();
    private final LongSupplier clock;
    private final BiConsumer<Long, Runnable> timer;
    private final Executor ui;
    private final Consumer<T> publish;
    private final int capacity;
    private long generation;
    private boolean scheduled;

    public TimedUiQueue(LongSupplier clock, BiConsumer<Long, Runnable> timer, Executor ui,
                        Consumer<T> publish, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid queue capacity");
        this.clock = clock; this.timer = timer; this.ui = ui; this.publish = publish; this.capacity = capacity;
    }
    public synchronized void add(T value, long delayMillis) {
        long delay = Math.clamp(delayMillis, 0, 60_000) * 1_000_000L;
        if (pending.size() == capacity) pending.removeFirst();
        pending.addLast(new Entry<>(value, clock.getAsLong() + delay));
        schedule();
    }
    public synchronized void cancel() {
        generation++; pending.clear(); scheduled = false;
    }
    private void schedule() {
        if (scheduled || pending.isEmpty()) return;
        scheduled = true;
        long ticket = generation;
        long delay = Math.max(0, pending.getFirst().due() - clock.getAsLong());
        try { timer.accept(delay, () -> ui.execute(() -> drain(ticket))); }
        catch (RuntimeException failure) { scheduled = false; throw failure; }
    }
    private synchronized void drain(long ticket) {
        if (ticket != generation) return;
        scheduled = false;
        while (ticket == generation && !pending.isEmpty() && pending.getFirst().due() - clock.getAsLong() <= 0)
            publish.accept(pending.removeFirst().value());
        if (ticket == generation) schedule();
    }
}
