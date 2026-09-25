package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FrameProgressUpdaterTest {
    @Test void initializesImmediatelyAndUpdatesTextOnlyWhenTheDisplayedSecondChanges() {
        var frames = new ArrayDeque<Runnable>();
        var time = new AtomicLong(15_200);
        var updates = new ArrayList<Boolean>();
        var loop = new FrameProgressUpdater(frames::add, time::get);
        loop.start(() -> true, time::get, 60_000, updates::add);
        assertEquals(List.of(true), updates);
        time.set(15_999); frames.remove().run();
        time.set(16_000); frames.remove().run();
        time.set(14_000); frames.remove().run(); // authoritative timeline correction
        assertEquals(List.of(true, false, true, true), updates);
        assertEquals(1, frames.size());
    }

    @Test void rapidReplacementAndLateOldFramesNeverPublishOrSchedule() {
        var frames = new ArrayDeque<Runnable>();
        var updates = new ArrayList<String>();
        var loop = new FrameProgressUpdater(frames::add, () -> 0);
        loop.start(() -> true, () -> 0, 1000, text -> updates.add("old"));
        loop.start(() -> true, () -> 0, 1000, text -> updates.add("new"));
        frames.remove().run();
        assertEquals(List.of("old", "new"), updates);
        assertEquals(1, frames.size());
        frames.remove().run();
        assertEquals(List.of("old", "new", "new"), updates);
        loop.stop(); frames.remove().run();
        assertTrue(frames.isEmpty());
        loop.start(() -> true, () -> 0, 1000, text -> updates.add("reopened"));
        assertEquals("reopened", updates.getLast());
    }

    @Test void changedSnapshotOrDetachedViewStopsThePendingFrame() {
        var frames = new ArrayDeque<Runnable>();
        var current = new AtomicBoolean(true);
        var updates = new ArrayList<Boolean>();
        var loop = new FrameProgressUpdater(frames::add, () -> 0);
        loop.start(current::get, () -> 0, 1000, updates::add);
        current.set(false); frames.remove().run();
        assertEquals(List.of(true), updates);
        assertTrue(frames.isEmpty());
    }

    @Test void stopDuringPublicationAndEndOfTrackDoNotReschedule() {
        var frames = new ArrayDeque<Runnable>();
        var loop = new FrameProgressUpdater(frames::add, () -> 0);
        loop.start(() -> true, () -> 0, 1000, text -> loop.stop());
        assertTrue(frames.isEmpty());
        var updates = new ArrayList<Boolean>();
        loop.start(() -> true, () -> 1000, 1000, updates::add);
        assertEquals(List.of(true), updates);
        assertTrue(frames.isEmpty());
    }

    @Test void unknownDurationAndUnstartedPlaybackHaveBoundedLifetime() {
        var frames = new ArrayDeque<Runnable>();
        var clock = new AtomicLong();
        var loop = new FrameProgressUpdater(frames::add, clock::get);
        loop.start(() -> true, () -> -10, 0, text -> {});
        clock.set(120_000); frames.remove().run();
        assertTrue(frames.isEmpty());
    }
}
