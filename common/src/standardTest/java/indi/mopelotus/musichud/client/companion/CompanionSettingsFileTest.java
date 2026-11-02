package indi.mopelotus.musichud.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompanionSettingsFileTest {
    @TempDir Path root;

    @Test void preservesLatin1AndEscapedUnicodeAndWindowsPaths() throws Exception {
        Path file = root.resolve("companion.properties");
        String text = "executable=C:" + "\\\\" + "Companion" + "\\\\" + "Companion.exe\nversion=0.2.0\ninstallDirectory=caf\u00e9/" + (char) 92 + "u97f3" + (char) 92 + "u4e50\n";
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file, bytes);
        var values = CompanionSettingsFile.load(file);
        assertEquals("C:\\Companion\\Companion.exe", values.getProperty("executable"));
        assertEquals("0.2.0", values.getProperty("version"));
        assertEquals("café/音乐", values.getProperty("installDirectory"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void malformedUnicodeDiscardsAllPreviouslyParsedValuesWithoutChangingFile() throws Exception {
        for (String suffix : new String[]{"uZZZZ", "u12", "u", "u123X"}) {
            Path file = root.resolve("bad.properties");
            byte[] bytes = ("executable=C:/valid/Companion.exe\nversion=0.2.0\ninstallDirectory=" + (char) 92 + suffix + "\n").getBytes(StandardCharsets.ISO_8859_1);
            Files.write(file, bytes);
            assertTrue(CompanionSettingsFile.load(file).isEmpty(), suffix);
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test void acceptsExactByteLimitButRejectsLimitPlusOneAsAWhole() throws Exception {
        Path file = root.resolve("large.properties");
        byte[] bytes = new byte[CompanionSettingsFile.MAX_BYTES];
        Arrays.fill(bytes, (byte) 'x');
        byte[] prefix = "version=0.2.0\n#".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        Files.write(file, bytes);
        assertEquals("0.2.0", CompanionSettingsFile.load(file).getProperty("version"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        byte[] tooLarge = Arrays.copyOf(bytes, bytes.length + 1);
        tooLarge[tooLarge.length - 1] = 'x';
        Files.write(file, tooLarge);
        assertTrue(CompanionSettingsFile.load(file).isEmpty());
        assertArrayEquals(tooLarge, Files.readAllBytes(file));
    }

    @Test void missingFilesAndDirectoriesProduceIndependentEmptyDefaults() throws Exception {
        Path missing = root.resolve("missing.properties");
        var first = CompanionSettingsFile.load(missing);
        first.setProperty("version", "mutated");
        assertTrue(CompanionSettingsFile.load(missing).isEmpty());
        assertFalse(Files.exists(missing));
        assertTrue(CompanionSettingsFile.load(root).isEmpty());
        assertTrue(Files.isDirectory(root));
    }

    @Test void refusesSymlinksWithoutReadingOrChangingTheirTargets() throws Exception {
        Path target = Files.writeString(root.resolve("real.properties"), "version=0.2.0\n", StandardCharsets.ISO_8859_1);
        Path link = root.resolve("linked.properties");
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
        assertTrue(CompanionSettingsFile.load(link).isEmpty());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("version=0.2.0\n", Files.readString(target, StandardCharsets.ISO_8859_1));
    }

    @Test void unreadableFileProducesEmptyDefaultsAndPreservesPermissions() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Path file = Files.writeString(root.resolve("private.properties"), "version=0.2.0\n", StandardCharsets.ISO_8859_1);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, Set.of());
            assumeFalse(Files.isReadable(file), "Current identity can bypass file permissions");
            assertTrue(CompanionSettingsFile.load(file).isEmpty());
            assertTrue(Files.getPosixFilePermissions(file).isEmpty());
        } finally { Files.setPosixFilePermissions(file, original); }
        assertEquals("version=0.2.0\n", Files.readString(file, StandardCharsets.ISO_8859_1));
    }
}
