package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedAudioSourcesTest {
    @Test void tracksContextAndDoesNotClaimReusedForeignIds() {
        var sources = new OwnedAudioSources();
        var lease = sources.register(100, 3);
        assertTrue(sources.owns(100, 3));
        assertFalse(sources.owns(200, 3));
        assertFalse(sources.owns(100, 4));
        sources.release(lease);
        assertFalse(sources.owns(100, 3));
    }

    @Test void lateReleaseCannotEraseNewOwnership() {
        var sources = new OwnedAudioSources();
        var old = sources.register(100, 3);
        var fresh = sources.register(100, 3);
        sources.release(old);
        assertTrue(sources.owns(100, 3));
        sources.release(fresh); sources.release(fresh);
        assertFalse(sources.owns(100, 3));
        assertThrows(IllegalArgumentException.class, () -> sources.register(0, 3));
        assertThrows(IllegalArgumentException.class, () -> sources.register(100, 0));
    }
}
