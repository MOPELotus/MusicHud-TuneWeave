package indi.mopelotus.musichud.companion.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanionArgumentsTest {
    @Test void helpDoesNotInitializeGraphicsOrConnectToTheGame() {
        assertDoesNotThrow(() -> Companion.main(new String[]{"--help"}));
    }
    @Test void versionDoesNotInitializeGraphicsOrConnectToTheGame() {
        assertEquals("0.2.0", Companion.version());
        assertDoesNotThrow(() -> Companion.main(new String[]{"--version"}));
    }
    @Test void absentOwnerExitsBeforeGraphics() {
        assertDoesNotThrow(() -> Companion.main(new String[]{"--owner-pid", "9223372036854775807", "--owner-start", "1"}));
    }
    @Test void rejectsInvalidOrIncompleteArgumentsBeforeOpeningAWindow() {
        for (String[] args : new String[][]{
                {"--port", "0"}, {"--port", "65536"}, {"--port", "-1"}, {"--port", "bad"},
                {"--x", "24"}, {"--y", "24"}, {"--x", "bad", "--y", "0"},
                {"--owner-pid", "1"}, {"--owner-start", "1"},
                {"--owner-pid", "0", "--owner-start", "1"},
                {"--owner-pid", "1", "--owner-start", "-1"},
                {"--owner-pid", "bad", "--owner-start", "1"},
                {"--owner-pid", "9223372036854775808", "--owner-start", "1"},
                {"--position-file"}, {"--position-file", ""}, {"--port"}, {"--unknown"}}) {
            assertThrows(IllegalArgumentException.class, () -> Companion.main(args), java.util.Arrays.toString(args));
        }
    }
}
