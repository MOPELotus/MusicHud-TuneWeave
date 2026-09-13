package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.ScrobbleMode;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackListeningLedgerTest {
    @Test void replacementRestoresOnlyConsumedAudioAndRejectsRetiredGeneration() {
        UUID session = UUID.randomUUID();
        var old = new PlaybackListeningLedger();
        old.begin(1, session, "netease:a");
        old.queue(1, 1, 60_000, 1000, true);
        old.observe(1, 12, true);
        var replacement = new PlaybackListeningLedger();
        replacement.begin(1, session, "netease:a");
        replacement.restoreConsumed(1, old.playedMillis(1));
        replacement.observe(1, 60, true);
        assertEquals(12_000, replacement.playedMillis(1), "queued audio must not survive replacement");
        replacement.begin(2, session, "netease:a");
        replacement.restoreConsumed(1, 60_000);
        replacement.queue(2, 1, 20_000, 1000, true);
        replacement.observe(2, 3, true);
        assertEquals(15_000, replacement.playedMillis(2));
        replacement.begin(3, session, "netease:b");
        assertEquals(0, replacement.playedMillis(3));
        assertThrows(IllegalArgumentException.class, () -> replacement.restoreConsumed(3, -1));
    }

    @Test void sameSessionDifferentResolvedTrackStartsSeparateListeningCount() {
        var ledger = new PlaybackListeningLedger(); UUID session = UUID.randomUUID();
        ledger.begin(1, session, "netease:a");
        ledger.queue(1, 1, 40_000, 1000, true); ledger.observe(1, 20, true);
        ledger.begin(2, session, "netease:a"); assertEquals(20_000, ledger.playedMillis(2));
        ledger.begin(3, session, "netease:b"); assertEquals(0, ledger.playedMillis(3));
    }

    @Test void countsOnlyConsumedMusicAndNeverQueuedSilence() {
        var ledger = new PlaybackListeningLedger();
        ledger.begin(1, UUID.randomUUID());
        ledger.queue(1, 1, 40_000, 1000, true);
        ledger.queue(1, 2, 10_000, 1000, false);
        ledger.queue(1, 3, 40_000, 1000, true);
        assertEquals(0, ledger.playedMillis(1));
        ledger.observe(1, 35, true);
        assertEquals(35_000, ledger.playedMillis(1));
        ledger.processed(1, 1);
        ledger.observe(1, 5, true);
        assertEquals(40_000, ledger.playedMillis(1));
        ledger.processed(1, 2);
        ledger.observe(1, 5, true);
        ledger.observe(1, 5, true);
        assertEquals(45_000, ledger.playedMillis(1));
    }

    @Test void muteSeekRetryAndStaleCallbacksDoNotInventListening() {
        var ledger = new PlaybackListeningLedger();
        UUID session = UUID.randomUUID();
        ledger.begin(1, session);
        ledger.queue(1, 1, 60_000, 1000, true);
        ledger.observe(1, 10, false);
        ledger.observe(1, 20, true);
        assertEquals(10_000, ledger.playedMillis(1));
        ledger.discardQueued(1);
        ledger.begin(2, session);
        ledger.observe(1, 60, true);
        ledger.queue(1, 2, 60_000, 1000, true);
        ledger.begin(1, UUID.randomUUID());
        ledger.queue(2, 1, 40_000, 1000, true);
        ledger.observe(2, 5, true);
        assertEquals(15_000, ledger.playedMillis(2));
        ledger.begin(3, UUID.randomUUID());
        assertEquals(0, ledger.playedMillis(3));
        assertEquals(0, ledger.playedMillis(2));
    }

    @Test void rejectsInvalidOffsetsAndDurations() {
        var ledger = new PlaybackListeningLedger();
        ledger.begin(1, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> ledger.queue(1, 1, -1, 1000, true));
        assertThrows(IllegalArgumentException.class, () -> ledger.queue(1, 1, 10, 0, true));
        ledger.queue(1, 1, 1000, 1000, true);
        for (double offset : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) ledger.observe(1, offset, true);
        assertEquals(0, ledger.playedMillis(1));
        ledger.observe(1, 1000, true);
        assertEquals(1000, ledger.playedMillis(1));
    }

    @Test void appliesPolicyThresholdAndSafeConfigFallback() {
        UUID self = UUID.randomUUID(), other = UUID.randomUUID();
        assertFalse(ScrobbleMode.NONE.allows(self, self, 60_000));
        assertFalse(ScrobbleMode.ONLY_SELF.allows(self, self, 29_999));
        assertTrue(ScrobbleMode.ONLY_SELF.allows(self, self, 30_000));
        assertFalse(ScrobbleMode.ONLY_SELF.allows(other, self, 30_000));
        assertFalse(ScrobbleMode.ONLY_SELF.allows(null, null, 30_000));
        assertTrue(ScrobbleMode.ALL.allows(other, self, 30_000));
        assertEquals(ScrobbleMode.ALL, ScrobbleMode.parse(" all "));
        assertEquals(ScrobbleMode.NONE, ScrobbleMode.parse(null));
        assertEquals(ScrobbleMode.NONE, ScrobbleMode.parse("invalid"));
    }
}
