package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TimedUiQueueTest {
    @Test void rapidLinesRemainOrderedAndUseOnlyOnePendingTimer() {
        var clock = new AtomicLong(); var timers = new ArrayDeque<Runnable>(); var shown = new ArrayList<String>();
        var queue = new TimedUiQueue<String>(clock::get, (delay, task) -> timers.add(task), Runnable::run, shown::add, 8);
        queue.add("a", 100); queue.add("b", 120); queue.add("c", 150);
        assertEquals(1, timers.size());
        clock.set(100_000_000); timers.remove().run(); assertEquals(List.of("a"), shown);
        clock.set(150_000_000); timers.remove().run(); assertEquals(List.of("a", "b", "c"), shown);
        assertTrue(timers.isEmpty());
    }
    @Test void cancelRejectsCallbacksAlreadyQueuedOnUiAndNewSessionStillRuns() {
        var clock = new AtomicLong(); var timers = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
        var shown = new ArrayList<String>();
        var queue = new TimedUiQueue<String>(clock::get, (delay, task) -> timers.add(task), ui::add, shown::add, 4);
        queue.add("old", 0); timers.remove().run(); queue.cancel(); queue.add("new", 0);
        ui.remove().run(); assertTrue(shown.isEmpty()); timers.remove().run(); ui.remove().run();
        assertEquals(List.of("new"), shown);
    }
    @Test void stalledRenderingHasBoundedBacklogAndCancellationInsidePublishIsAtomic() {
        var timers = new ArrayDeque<Runnable>(); var shown = new ArrayList<Integer>();
        var queue = new TimedUiQueue<Integer>(() -> 0, (delay, task) -> timers.add(task), Runnable::run, shown::add, 3);
        for (int i=0;i<1000;i++) queue.add(i,0);
        timers.remove().run(); assertEquals(List.of(997,998,999), shown);
        var holder = new java.util.concurrent.atomic.AtomicReference<TimedUiQueue<Integer>>();
        holder.set(new TimedUiQueue<>(() -> 0, (delay, task) -> timers.add(task), Runnable::run,
                value -> { shown.add(value); holder.get().cancel(); }, 3));
        holder.get().add(1,0); holder.get().add(2,0); timers.remove().run();
        assertEquals(List.of(997,998,999,1), shown);
    }
}
