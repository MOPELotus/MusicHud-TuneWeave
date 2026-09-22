package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackDownloadProgressTest {
    @Test void catchupReadsCannotKeepAnEmptyPlayerAliveForeverButQueuedAudioIsNotAStall() {
        var clock = new AtomicLong(); var progress = new PlaybackDownloadProgress(clock::get);
        clock.set(PlaybackDownloadProgress.STALL_NANOS - 1); progress.beforeRead(true);
        clock.incrementAndGet(); progress.beforeRead(false);
        assertThrows(IllegalStateException.class, () -> progress.beforeRead(true));
        progress.enqueued(); progress.beforeRead(true);
    }
    @Test void eofToleranceCoversMetadataRoundingAndUnknownDuration() {
        assertFalse(PlaybackDownloadProgress.truncated(0, 48000, 0));
        assertFalse(PlaybackDownloadProgress.truncated(48000L * 171, 48000, 180000));
        assertTrue(PlaybackDownloadProgress.truncated(48000L * 170, 48000, 180000));
        assertFalse(PlaybackDownloadProgress.truncated(48000L * 8, 48000, 10000));
        assertTrue(PlaybackDownloadProgress.truncated(48000L * 7, 48000, 10000));
    }
    @Test void publicRefreshesAreBoundedAcrossRevisionsAndRejectRetiredSessions() {
        var budget = new EarlyEofRecovery(); var first = UUID.randomUUID(); var next = UUID.randomUUID();
        budget.activate(first);
        assertTrue(budget.claim(first, 0)); assertFalse(budget.claim(first, 0));
        budget.activate(first); assertTrue(budget.claim(first, 1)); assertTrue(budget.claim(first, 2));
        assertFalse(budget.claim(first, 3));
        budget.activate(next); assertFalse(budget.claim(first, 4)); assertTrue(budget.claim(next, 0));
    }
}
