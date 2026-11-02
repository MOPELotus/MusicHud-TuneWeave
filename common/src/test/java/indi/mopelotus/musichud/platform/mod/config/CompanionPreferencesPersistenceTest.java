package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompanionPreferencesPersistenceTest {
    @TempDir Path directory;

    private Path configFile() { return directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-client.toml"); }
    private ClientConfigDefinition load() {
        var config = new ClientConfigDefinition(directory.resolve("private/client-accounts.json"));
        config.load();
        return config;
    }

    @Test void enabledFrameLimitAndScaleSurviveFreshInstancesAndExplicitDisable() throws Exception {
        Path previous = MusicHud.getConfigDirectory();
        MusicHud.setConfigDirectory(directory);
        try {
            var config = load();
            assertFalse(config.getCompanionEnabled());
            assertEquals(60, config.getCompanionFps());
            assertEquals(2, config.getCompanionScale());
            config.setCompanionEnabled(true);
            config.setCompanionFps(30);
            config.setCompanionScale(4);
            var restored = load();
            assertTrue(restored.getCompanionEnabled());
            assertEquals(30, restored.getCompanionFps());
            assertEquals(4, restored.getCompanionScale());
            restored.setCompanionEnabled(false);
            restored.setCompanionFps(60);
            restored.setCompanionScale(1);
            var disabled = load();
            assertFalse(disabled.getCompanionEnabled());
            assertEquals(60, disabled.getCompanionFps());
            assertEquals(1, disabled.getCompanionScale());
            String text = Files.readString(configFile());
            assertTrue(text.contains("companionEnabled = false"));
            assertTrue(text.contains("companionFps = 60"));
            assertTrue(text.contains("companionScale = 1"));
            assertFalse(text.contains("companionLocked"), "Connection locking stays transient");
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void malformedMissingAndOutOfRangeValuesRestoreDefaultsRatherThanPreviousChoice() throws Exception {
        Path previous = MusicHud.getConfigDirectory();
        MusicHud.setConfigDirectory(directory);
        try {
            var config = load();
            for (String invalid : new String[]{
                    "companionEnabled = yes\ncompanionFps = 31\ncompanionScale = 0\n",
                    "companionEnabled = 1\ncompanionFps = -30\ncompanionScale = 5\n",
                    "companionEnabled = []\ncompanionFps = 2147483648\ncompanionScale = -2147483649\n",
                    "companionEnabled = invalid\ncompanionFps = 30.5\ncompanionScale = 2.5\n",
                    "soundVolume = 25\n"}) {
                config.setCompanionEnabled(true);
                config.setCompanionFps(30);
                config.setCompanionScale(4);
                Files.writeString(configFile(), invalid);
                config.load();
                assertFalse(config.getCompanionEnabled(), invalid);
                assertEquals(60, config.getCompanionFps(), invalid);
                assertEquals(2, config.getCompanionScale(), invalid);
            }
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void publicSettersNormalizeSupportedValuesBeforePersisting() throws Exception {
        Path previous = MusicHud.getConfigDirectory();
        MusicHud.setConfigDirectory(directory);
        try {
            var config = load();
            config.setCompanionScale(Integer.MIN_VALUE);
            config.setCompanionFps(Integer.MAX_VALUE);
            var first = load();
            assertEquals(1, first.getCompanionScale());
            assertEquals(60, first.getCompanionFps());
            first.setCompanionScale(Integer.MAX_VALUE);
            first.setCompanionFps(Integer.MIN_VALUE);
            var second = load();
            assertEquals(4, second.getCompanionScale());
            assertEquals(30, second.getCompanionFps());
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void unreadableEncodingPreservesDamagedFileAndDoesNotEnableDesktopByDefault() throws Exception {
        Path previous = MusicHud.getConfigDirectory();
        MusicHud.setConfigDirectory(directory);
        try {
            byte[] damaged = {(byte) 0xc3, (byte) 0x28};
            Files.write(configFile(), damaged);
            var config = load();
            assertFalse(config.getCompanionEnabled());
            assertEquals(60, config.getCompanionFps());
            assertEquals(2, config.getCompanionScale());
            config.save();
            assertArrayEquals(damaged, Files.readAllBytes(configFile()));
        } finally { MusicHud.setConfigDirectory(previous); }
    }
}
