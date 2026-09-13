package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackStartGateTest {
    @Test void replacementAndStopCompleteOldStartupEvenIfDecoderNeverReturns() {
        var gate = new PlaybackStartGate();
        var old = gate.begin();
        var next = gate.begin();
        assertTrue(old.isCancelled());
        assertFalse(old.complete(ZonedDateTime.now()));
        assertFalse(next.isDone());
        gate.cancel(); gate.cancel();
        assertTrue(next.isCancelled());
        assertFalse(next.complete(ZonedDateTime.now()));
    }

    @Test void engineStartupCoordinatorCancelsRealHandoffOnRapidReplacementAndStop() {
        var frames = new ArrayList<Runnable>();
        var handoff = new PlaybackHandoff<Lane>(Runnable::run, frames::add, () -> 0);
        var first = new Lane(); var second = new Lane();
        var firstResult = handoff.begin(first, Lane::start);
        assertFalse(firstResult.isDone());
        var secondResult = handoff.begin(second, Lane::start);
        assertTrue(first.started.isCancelled());
        assertTrue(firstResult.isCompletedExceptionally());
        assertFalse(first.started.complete(ZonedDateTime.now()));
        handoff.stop();
        assertTrue(second.started.isCancelled());
        assertTrue(secondResult.isCompletedExceptionally());
        assertFalse(second.started.complete(ZonedDateTime.now()));
        assertNull(handoff.current());
        assertEquals(1, first.discards); assertEquals(1, second.discards);
    }

    private static final class Lane implements PlaybackHandoff.Lane {
        private final PlaybackStartGate gate = new PlaybackStartGate();
        private CompletableFuture<ZonedDateTime> started;
        private int discards;
        CompletableFuture<ZonedDateTime> start() { return started = gate.begin(); }
        public void gain(float gain) {}
        public void discard() { discards++; gate.cancel(); }
    }
}
