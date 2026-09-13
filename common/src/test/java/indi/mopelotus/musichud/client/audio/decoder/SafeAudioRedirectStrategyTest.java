package indi.mopelotus.musichud.client.audio.decoder;

import org.apache.http.ProtocolException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeAudioRedirectStrategyTest {
    @Test
    void validatesEveryRedirectTarget() throws Exception {
        URI publicTarget = URI.create("https://8.8.8.8/audio");
        assertEquals(publicTarget, SafeAudioRedirectStrategy.requireSafeLocation(publicTarget));

        assertThrows(ProtocolException.class, () -> SafeAudioRedirectStrategy.requireSafeLocation(
                URI.create("http://127.0.0.1/internal")));
        assertThrows(ProtocolException.class, () -> SafeAudioRedirectStrategy.requireSafeLocation(
                URI.create("http://169.254.169.254/latest/meta-data")));
    }
}
