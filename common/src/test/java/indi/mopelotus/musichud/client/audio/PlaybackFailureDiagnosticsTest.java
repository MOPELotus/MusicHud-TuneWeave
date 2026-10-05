package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackFailureDiagnosticsTest {
    @Test void reportsWrappedTimeoutAndCodeLocationWithoutSensitiveMessages() {
        Throwable failure = new CompletionException("https://cdn.invalid/audio?token=secret",
                new TimeoutException("Cookie: private-account"));
        String diagnostic = PlaybackFailureDiagnostics.describe(failure);
        assertEquals("timeout", PlaybackFailureDiagnostics.kind(failure));
        assertTrue(diagnostic.contains("CompletionException"));
        assertTrue(diagnostic.contains("TimeoutException"));
        assertTrue(diagnostic.contains("PlaybackFailureDiagnosticsTest"));
        assertFalse(diagnostic.contains("secret"));
        assertFalse(diagnostic.contains("Cookie"));
        assertFalse(diagnostic.contains("cdn.invalid"));
    }

    @Test void distinguishesCancellationAndBoundsCyclicCauses() {
        assertEquals("cancelled", PlaybackFailureDiagnostics.kind(new CompletionException(new CancellationException())));
        Exception first = new Exception("first"), second = new Exception("second");
        first.initCause(second); second.initCause(first);
        assertEquals("failure", PlaybackFailureDiagnostics.kind(first));
        assertTrue(PlaybackFailureDiagnostics.describe(first).length() < 10000);
    }
}
