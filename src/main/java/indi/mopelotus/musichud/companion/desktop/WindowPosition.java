package indi.mopelotus.musichud.companion.desktop;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/** Stores only geometry. Never persists the unlocked state. */
final class WindowPosition {
    private final Path path;
    WindowPosition(Path path) { this.path = path; }

    int[] load() {
        Properties values = new Properties();
        try (var input = Files.newInputStream(path)) {
            values.load(input);
            return new int[]{Integer.parseInt(values.getProperty("x")), Integer.parseInt(values.getProperty("y"))};
        } catch (IOException | IllegalArgumentException error) { return null; }
    }

    void save(int x, int y) {
        Path temporary = null;
        try {
            Path directory = path.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "companion-position-", ".tmp");
            Properties values = new Properties();
            values.setProperty("x", Integer.toString(x));
            values.setProperty("y", Integer.toString(y));
            try (var output = Files.newOutputStream(temporary)) { values.store(output, "Companion window position"); }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { System.err.println("Could not save HUD position: " + error.getMessage()); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    static boolean intersects(int x, int y, int width, int height, int mx, int my, int mw, int mh) {
        return (long) x + width > mx && (long) y + height > my && (long) mx + mw > x && (long) my + mh > y;
    }
}
