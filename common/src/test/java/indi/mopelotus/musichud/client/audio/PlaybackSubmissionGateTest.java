package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackSubmissionGateTest {
    @Test void replacementCarriesClaimWithoutBlockingTheNextSession() {
        UUID session = UUID.randomUUID();
        var old = new PlaybackSubmissionGate();
        old.activate(4, session);
        assertFalse(old.isClaimed(4));
        assertTrue(old.claim(4, true));
        assertFalse(old.isClaimed(3));
        var replacement = new PlaybackSubmissionGate();
        replacement.activate(1, session);
        if (old.isClaimed(4)) replacement.claim(1, true);
        assertFalse(replacement.claim(1, true));
        replacement.activate(2, UUID.randomUUID());
        assertTrue(replacement.claim(2, true));
    }

    @Test void eachTrackCanSubmitButCompletionAndStopCannotDuplicate() {
        var gate = new PlaybackSubmissionGate();
        gate.activate(1, UUID.randomUUID());
        assertTrue(gate.claim(1, true));
        assertFalse(gate.claim(1, true));
        gate.activate(2, UUID.randomUUID());
        assertTrue(gate.claim(2, true));
    }

    @Test void invalidMetadataDoesNotConsumeTheClaim() {
        var gate = new PlaybackSubmissionGate();
        gate.activate(1, UUID.randomUUID());
        assertFalse(gate.claim(1, false));
        assertTrue(gate.claim(1, true));
    }

    @Test void refreshAndRetryKeepSessionClaimAndRejectOldCallbacks() {
        var gate = new PlaybackSubmissionGate();
        UUID session = UUID.randomUUID();
        gate.activate(1, session);
        gate.activate(2, session);
        assertFalse(gate.claim(1, true));
        assertTrue(gate.claim(2, true));
        gate.activate(3, session);
        assertFalse(gate.claim(3, true));
        gate.activate(1, UUID.randomUUID());
        assertFalse(gate.claim(1, true));
    }

    @Test void stoppedSessionCannotBeRevivedByLateCompletion() {
        var gate = new PlaybackSubmissionGate();
        gate.activate(1, UUID.randomUUID());
        gate.invalidate(2);
        gate.activate(1, UUID.randomUUID());
        assertFalse(gate.claim(1, true));
        gate.activate(3, UUID.randomUUID());
        assertTrue(gate.claim(3, true));
    }
}
