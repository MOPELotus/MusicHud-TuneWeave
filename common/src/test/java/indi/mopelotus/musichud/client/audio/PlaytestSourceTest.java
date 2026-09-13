package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlaytestSourceTest {
    @Test void acceptsQuotedLocalPathsInBothSingleAndMultiplayerWithoutRewritingTheirContents() {
        assertEquals("C:\\Music files\\track.flac", PlaytestSource.parse(" \"C:\\Music files\\track.flac\" "));
        assertEquals("https://example.com/track.flac", PlaytestSource.parse("https://example.com/track.flac"));
        for (String value : List.of("", "  ", "\"\"", "\"unclosed", "track\nfile", "x".repeat(8193)))
            assertThrows(IllegalArgumentException.class, () -> PlaytestSource.parse(value));
    }
}
