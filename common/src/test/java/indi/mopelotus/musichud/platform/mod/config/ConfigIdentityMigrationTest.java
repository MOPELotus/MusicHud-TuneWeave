package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.beans.music.ScrobbleMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigIdentityMigrationTest {
    @TempDir Path directory;

    @Test void configLoadAndSaveUsesOnlyProjectOwnedName() throws Exception {
        String original = "soundVolume = 37\nscrobbleMode = \"ALL\"\n";
        Path active = directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-client.toml"); Files.writeString(active, original);
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(directory);
        try {
            var constructor = ClientConfigDefinition.class.getDeclaredConstructor(); constructor.setAccessible(true);
            var config = constructor.newInstance(); config.load();
            assertEquals(37, config.getSoundVolume()); assertEquals(ScrobbleMode.ALL, config.getScrobbleMode());
            config.setSoundVolume(63); config.save();
            assertEquals("63", SimpleTomlConfig.read(active).get("soundVolume"));
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void audioOutputPreferenceLoadsSavesAndRejectsUnknownValues() throws Exception {
        Path active = directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-client.toml");
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(directory);
        try {
            var constructor = ClientConfigDefinition.class.getDeclaredConstructor(); constructor.setAccessible(true);
            var config = constructor.newInstance();
            Files.writeString(active, "audioOutputMode = \"STEREO\"\n"); config.load();
            assertEquals(indi.mopelotus.musichud.beans.music.AudioOutputMode.STEREO, config.getAudioOutputMode());
            config.save(); assertEquals("STEREO", SimpleTomlConfig.read(active).get("audioOutputMode"));
            Files.writeString(active, "audioOutputMode = \"unknown\"\n"); config.load();
            assertEquals(indi.mopelotus.musichud.beans.music.AudioOutputMode.MULTICHANNEL, config.getAudioOutputMode());
            config.setAudioOutputMode(null);
            assertEquals(indi.mopelotus.musichud.beans.music.AudioOutputMode.MULTICHANNEL, config.getAudioOutputMode());
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void currentServerConfigIsProjectOwned() throws Exception {
        Path active = directory.resolve(ProjectIdentity.CONFIG_PREFIX + "-server.toml"); Files.writeString(active, "port = 7002\n");
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(directory);
        try {
            var constructor = ServerConfigDefinition.class.getDeclaredConstructor(); constructor.setAccessible(true);
            var config = constructor.newInstance(); config.load();
            assertEquals(7002, config.getPort());
        } finally { MusicHud.setConfigDirectory(previous); }
    }
}