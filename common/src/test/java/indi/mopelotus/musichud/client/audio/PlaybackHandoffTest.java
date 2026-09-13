package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackHandoffTest {
    @Test void synchronousStarterFailureAndStopDuringFadeReleaseEveryLane() {
        List<Runnable> frames = new ArrayList<>();
        var handoff = new PlaybackHandoff<Lane>(Runnable::run, frames::add, () -> 0);
        var failed = new Lane();
        assertTrue(handoff.begin(failed, lane -> { throw new IllegalStateException("open failed"); }).isCompletedExceptionally());
        assertEquals(1, failed.discards);
        var a = new Lane(); var b = new Lane();
        handoff.begin(a, lane -> CompletableFuture.completedFuture(ZonedDateTime.now()));
        handoff.begin(b, lane -> CompletableFuture.completedFuture(ZonedDateTime.now()));
        handoff.stop(); frames.forEach(Runnable::run);
        assertEquals(1, a.discards); assertEquals(1, b.discards); assertNull(handoff.current());
    }

    @Test void keepsCurrentAudibleUntilCandidateReadyThenFadesAndClosesOld() {
        var clock = new AtomicLong(); List<Runnable> frames = new ArrayList<>();
        var handoff = new PlaybackHandoff<Lane>(Runnable::run, frames::add, clock::get);
        var first = new Lane(); handoff.begin(first, lane -> lane.ready); first.ready.complete(ZonedDateTime.now());
        clock.set(150_000_000); frames.removeFirst().run(); assertEquals(1, first.gain);
        var next = new Lane(); var result = handoff.begin(next, lane -> lane.ready);
        assertSame(first, handoff.current()); assertEquals(1, first.gain); assertEquals(0, next.gain);
        next.ready.complete(ZonedDateTime.now()); assertTrue(result.isDone()); assertSame(next, handoff.current());
        clock.set(225_000_000); frames.removeFirst().run();
        assertEquals(.5f, next.gain); assertEquals(.5f, first.gain); assertEquals(0, first.discards);
        clock.set(300_000_000); frames.removeFirst().run();
        assertEquals(1, next.gain); assertEquals(1, first.discards);
    }

    @Test void rapidReplacementAndStopRejectLatePreparations() {
        List<Runnable> starts = new ArrayList<>(), frames = new ArrayList<>();
        var handoff = new PlaybackHandoff<Lane>(starts::add, frames::add, () -> 0);
        var a = new Lane(); var b = new Lane(); var c = new Lane();
        var ar = handoff.begin(a, lane -> lane.ready);
        var br = handoff.begin(b, lane -> lane.ready);
        var cr = handoff.begin(c, lane -> lane.ready);
        while (!starts.isEmpty()) starts.removeFirst().run();
        assertTrue(ar.isCompletedExceptionally()); assertTrue(br.isCompletedExceptionally());
        assertEquals(1, a.discards); assertEquals(1, b.discards);
        handoff.stop(); c.ready.complete(ZonedDateTime.now());
        assertTrue(cr.isCompletedExceptionally()); assertNull(handoff.current()); assertEquals(1, c.discards);
    }

    @Test void preparationFailureLeavesCurrentUntouchedAndSameLaneRefreshDoesNotDisposeIt() {
        List<Runnable> frames = new ArrayList<>(); var clock = new AtomicLong();
        var handoff = new PlaybackHandoff<Lane>(Runnable::run, frames::add, clock::get);
        var active = new Lane(); handoff.begin(active, lane -> lane.ready); active.ready.complete(ZonedDateTime.now());
        var failed = new Lane(); var failure = handoff.begin(failed, lane -> lane.ready);
        failed.ready.completeExceptionally(new IllegalStateException("offline"));
        assertTrue(failure.isCompletedExceptionally()); assertSame(active, handoff.current()); assertEquals(1, active.gain);
        assertEquals(1, failed.discards); assertEquals(0, active.discards);
        handoff.begin(active, lane -> CompletableFuture.completedFuture(ZonedDateTime.now())).join();
        assertEquals(0, active.discards);
        handoff.stop(); frames.forEach(Runnable::run); assertNull(handoff.current()); assertEquals(1, active.discards);
    }

    private static final class Lane implements PlaybackHandoff.Lane {
        final CompletableFuture<ZonedDateTime> ready = new CompletableFuture<>();
        float gain; int discards;
        public void gain(float gain) { this.gain = gain; }
        public void discard() { discards++; }
    }
}
