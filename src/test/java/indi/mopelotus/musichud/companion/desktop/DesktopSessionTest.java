package indi.mopelotus.musichud.companion.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSessionTest {
    @TempDir Path directory;
    @Test void rejectsDuplicateWithoutRotatingTheActiveLogAndReleasesOnClose() throws Exception {
        Path log = directory.resolve("companion-39841.log");
        Files.writeString(log, "existing");
        try (var first = new DesktopSession(directory, 39841)) {
            System.out.println("session-test-output");
            assertThrows(IOException.class, () -> new DesktopSession(directory, 39841));
            assertTrue(Files.readString(log).contains("session-test-output"));
            assertFalse(Files.exists(directory.resolve("companion-39841.previous.log")));
        }
        try (var reopened = new DesktopSession(directory, 39841)) {
            System.err.println("session-test-reopened");
        }
        assertTrue(Files.readString(log).contains("session-test-reopened"));
    }
}
