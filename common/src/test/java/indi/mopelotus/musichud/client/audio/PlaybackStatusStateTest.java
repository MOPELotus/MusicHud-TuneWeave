package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static indi.mopelotus.musichud.client.audio.StreamAudioPlayer.Status;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackStatusStateTest {
    @Test void failedPublicReplacementStaysErrorAfterRetainedLaneEnds() {
        Fixture f = new Fixture();
        Lane old = f.start(); old.ready.complete(ZonedDateTime.now());
        f.status.observe(Status.IDLE);
        Lane next = f.start(); next.ready.completeExceptionally(new IllegalStateException("TLS failed"));
        assertSame(old, f.handoff.current(), "Failure still preserves the previous lane");
        assertTrue(next.discarded);
        assertFalse(old.discarded);
        assertEquals(Status.ERROR, f.status.get());
        f.status.observe(Status.IDLE);
        f.status.observe(Status.PLAYING);
        assertEquals(Status.ERROR, f.status.get(), "Late old-lane notifications cannot hide failure");
    }

    @Test void explicitRetryClearsFailureAndSuccessfulLaneCanFinishNormally() {
        Fixture f = new Fixture();
        f.start().ready.completeExceptionally(new IllegalStateException("offline"));
        assertEquals(Status.ERROR, f.status.get());
        Lane retry = f.start();
        assertEquals(Status.BUFFERING, f.status.get());
        retry.ready.complete(ZonedDateTime.now());
        assertEquals(Status.PLAYING, f.status.get());
        f.status.observe(Status.IDLE);
        assertEquals(Status.IDLE, f.status.get());
    }

    @Test void replacedAndStoppedPreparationsCannotPublishTheirLateResult() {
        Fixture f = new Fixture();
        Lane a = f.start(), b = f.start();
        a.ready.completeExceptionally(new IllegalStateException("late error"));
        assertEquals(Status.BUFFERING, f.status.get());
        f.stop(); b.ready.complete(ZonedDateTime.now());
        assertEquals(Status.IDLE, f.status.get());
        assertNull(f.handoff.current());
        assertTrue(a.discarded); assertTrue(b.discarded);
    }

    @Test void oldCompletionCannotClearNewFailureAndStopClearsItsLatch() {
        Fixture f = new Fixture();
        long old = f.status.begin(), current = f.status.begin();
        f.status.complete(current, Status.IDLE, new IllegalStateException("failed"));
        f.status.complete(old, Status.PLAYING, null);
        assertEquals(Status.ERROR, f.status.get());
        f.stop(); assertEquals(Status.IDLE, f.status.get());
        Lane restart = f.start(); restart.ready.complete(ZonedDateTime.now());
        assertEquals(Status.PLAYING, f.status.get());
    }

    @Test void handoffCompletionGapCannotPublishOldIdleBeforeLatestResult() {
        Fixture f = new Fixture();
        long ticket = f.status.begin();
        // Handoff has cleared pending, but its result callback has not yet acquired the facade lock.
        assertFalse(f.handoff.preparing());
        f.status.observe(Status.IDLE);
        assertEquals(Status.BUFFERING, f.status.get());
        f.status.complete(ticket, Status.IDLE, new IllegalStateException("failed"));
        assertEquals(Status.ERROR, f.status.get());
    }

    @Test void oldLaneCannotPublishPlayingDuringPreparationAndEventsAreDeduplicated() {
        Fixture f = new Fixture();
        Lane old = f.start(); old.ready.complete(ZonedDateTime.now());
        f.start();
        f.status.observe(Status.PLAYING);
        f.status.observe(Status.IDLE);
        assertEquals(List.of(Status.BUFFERING, Status.PLAYING, Status.BUFFERING), f.events);
        f.stop();
    }

    private static final class Fixture {
        final List<Status> events = new ArrayList<>();
        final PlaybackStatusState status = new PlaybackStatusState(events::add);
        final PlaybackHandoff<Lane> handoff = new PlaybackHandoff<>(Runnable::run, task -> {}, () -> 0);
        Lane start() {
            Lane lane = new Lane(); long ticket = status.begin();
            handoff.begin(lane, candidate -> candidate.ready)
                    .whenComplete((time, error) -> status.complete(ticket, Status.PLAYING, error));
            return lane;
        }
        void stop() { status.invalidate(); handoff.stop(); status.observe(Status.IDLE); }
    }

    private static final class Lane implements PlaybackHandoff.Lane {
        final CompletableFuture<ZonedDateTime> ready = new CompletableFuture<>();
        boolean discarded;
        public void gain(float gain) {}
        public void discard() { discarded = true; }
    }
}
