package indi.mopelotus.musichud.client.utils.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricRowMotionTest {
    @Test void rapidHighlightRebaseKeepsRenderedPositionAndEventuallyReleasesCompensation() {
        var row = new LyricRowMotion(); long now = 1_000_000_000L;
        row.jumpTo(30); row.snapProgress(0); row.retarget(0, now); row.retargetProgress(1, now);
        row.update(now + 90_000_000L);
        float compensation = 18;
        float before = row.getValue() + compensation * (1 - row.getProgress());
        row.translate(compensation * (1 - row.getProgress())); row.snapProgress(0);
        assertEquals(before, row.getValue(), .0001f);
        row.retarget(28, now + 90_000_000L); row.retargetProgress(1, now + 90_000_000L);
        row.update(now + 5_000_000_000L);
        assertEquals(28, row.getValue(), .01f); assertEquals(1, row.getProgress(), .01f); assertTrue(row.isSettled());
    }

    @Test void manualScrollRecoveryUsesCurrentSpringPositionAndNewRowsStartIndependent() {
        var old = new LyricRowMotion(); old.jumpTo(28); old.retarget(0, 1_000_000_000L);
        old.update(1_080_000_000L); float before = old.getValue();
        old.retarget(28, 1_080_000_000L); old.update(1_080_000_000L);
        assertEquals(before, old.getValue(), .0001f);
        var fresh = new LyricRowMotion(); fresh.jumpTo(0);
        old.update(6_000_000_000L); assertEquals(28, old.getValue(), .01f); assertEquals(0, fresh.getValue());
    }
}
