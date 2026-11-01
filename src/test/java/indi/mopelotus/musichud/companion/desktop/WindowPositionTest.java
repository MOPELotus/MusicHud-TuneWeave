package indi.mopelotus.musichud.companion.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowPositionTest {
    @TempDir Path directory;
    @Test void persistsOnlyGeometryAndToleratesCorruptFiles() throws Exception {
        Path file = directory.resolve("position.properties");
        var position = new WindowPosition(file);
        assertNull(position.load());
        position.save(-1920, 40);
        assertArrayEquals(new int[]{-1920, 40}, position.load());
        assertFalse(Files.readString(file).contains("locked"));
        Files.writeString(file, "x=broken\ny=2");
        assertNull(position.load());
    }
    @Test void handlesNegativeMonitorCoordinatesAndIntegerOverflow() {
        assertTrue(WindowPosition.intersects(-1900, 100, 500, 100, -1920, 0, 1920, 1080));
        assertFalse(WindowPosition.intersects(Integer.MAX_VALUE, 0, 500, 100, 0, 0, 1920, 1080));
    }
    @Test void preservesUnrelatedTemporaryAndUserFiles() throws Exception {
        Path sibling = directory.resolve("position.properties.tmp");
        Files.writeString(sibling, "user data");
        var position = new WindowPosition(directory.resolve("position.properties"));
        position.save(10, 20);
        assertEquals("user data", Files.readString(sibling));
        assertArrayEquals(new int[]{10, 20}, position.load());
    }
    @Test void failedReplacementRetainsTheExistingDirectoryAndCleansOnlyItsOwnTemporary() throws Exception {
        Path target = Files.createDirectory(directory.resolve("position.properties"));
        Path sentinel = target.resolve("user.txt");
        Files.writeString(sentinel, "retained");
        new WindowPosition(target).save(10, 20);
        assertTrue(Files.isDirectory(target));
        assertEquals("retained", Files.readString(sentinel));
        try (var files = Files.list(directory)) {
            assertEquals(java.util.List.of(target), files.toList());
        }
    }
}
