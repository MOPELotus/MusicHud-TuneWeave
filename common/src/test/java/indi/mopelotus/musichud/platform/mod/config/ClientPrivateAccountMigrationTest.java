package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClientPrivateAccountMigrationTest {
    @TempDir Path directory;
    private Path configFile() { return directory.resolve("config").resolve(ProjectIdentity.CONFIG_PREFIX + "-client.toml"); }
    private Path accountsFile() { return directory.resolve("api/client-accounts.json"); }

    private void writeLegacy(String credentials, String profile) {
        SimpleTomlConfig.write(configFile(), List.of(
                new SimpleTomlConfig.Entry("soundVolume", "", 43),
                new SimpleTomlConfig.Entry("tuneWeaveCredentials", "", credentials),
                new SimpleTomlConfig.Entry("clientAccountConfig", "", profile)));
    }

    @Test void migrationPersistsCredentialsAndProfileBeforeRemovingExportableConfigFields() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            writeLegacy("{\"netease\":\"legacy-fixture\",\"qq\":\"qq-fixture\"}", "{\"idlePlaySources\":[]}");
            var config = new ClientConfigDefinition(accountsFile()); config.load();
            assertEquals("legacy-fixture", config.getTuneWeaveCredential("netease"));
            assertEquals("qq-fixture", config.getTuneWeaveCredential("qq"));
            assertEquals(43, config.getSoundVolume());
            var values = SimpleTomlConfig.read(configFile());
            assertFalse(values.containsKey("tuneWeaveCredentials"));
            assertFalse(values.containsKey("clientAccountConfig"));
            assertFalse(Files.readString(configFile()).contains("fixture"));
            var persisted = new ClientAccountStore(accountsFile());
            assertEquals("legacy-fixture", persisted.credential("netease"));
            assertTrue(persisted.profileData().getAsJsonObject().has("idlePlaySources"));
            var reloaded = new ClientConfigDefinition(accountsFile()); reloaded.load();
            assertEquals(1, reloaded.getSavedAccounts("netease").size());
            assertEquals("legacy-fixture", reloaded.getTuneWeaveCredential("netease"));
            reloaded.storeLoginCredential("netease", "second-fixture");
            reloaded.save();
            assertFalse(Files.readString(configFile()).contains("fixture"));
            assertEquals(2, new ClientAccountStore(accountsFile()).accounts("netease").size());
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void failedPersistenceRetainsExactOriginalConfigAndRetryMigratesOnce() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            writeLegacy("{\"netease\":\"only-copy-fixture\"}", "{\"idlePlaySources\":[]}");
            String original = Files.readString(configFile());
            Files.writeString(directory.resolve("api"), "unrelated-file");
            var config = new ClientConfigDefinition(accountsFile()); config.load();
            config.setSoundVolume(12); config.save();
            assertEquals(original, Files.readString(configFile()));
            assertEquals("unrelated-file", Files.readString(directory.resolve("api")));
            assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "new-fixture"));
            assertTrue(config.getSavedAccounts("netease").isEmpty());
            Files.delete(directory.resolve("api")); config.load();
            assertEquals("only-copy-fixture", config.getTuneWeaveCredential("netease"));
            assertEquals(1, config.getSavedAccounts("netease").size());
            config.load();
            assertEquals(1, config.getSavedAccounts("netease").size());
            assertFalse(SimpleTomlConfig.read(configFile()).containsKey("tuneWeaveCredentials"));
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void malformedLegacyDataPreservesBothExistingStorageAndConfigAndRejectsWrites() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            var store = new ClientAccountStore(accountsFile()); store.setCredential("qq", "existing-fixture", true);
            String privateBefore = Files.readString(accountsFile());
            for (String legacy : List.of("{", "null", "{\"netease\":true}", "{\"netease\":\"one\",\"netease\":\"two\"}")) {
                writeLegacy(legacy, ""); String configBefore = Files.readString(configFile());
                var config = new ClientConfigDefinition(accountsFile()); config.load(); config.save();
                assertEquals(configBefore, Files.readString(configFile()));
                assertEquals(privateBefore, Files.readString(accountsFile()));
                assertEquals("existing-fixture", config.getTuneWeaveCredential("qq"));
                assertThrows(IllegalStateException.class, () -> config.clearTuneWeaveCredential("qq"));
            }
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void corruptPrivateStorageDoesNotEraseOldCredentialsOrBreakOrdinarySettingsReads() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            writeLegacy("{\"netease\":\"only-copy-fixture\"}", "");
            String configBefore = Files.readString(configFile());
            Files.createDirectories(accountsFile().getParent()); Files.writeString(accountsFile(), "broken-fixture");
            var config = new ClientConfigDefinition(accountsFile()); config.load();
            assertEquals(43, config.getSoundVolume());
            assertEquals("", config.getTuneWeaveCredential("netease"));
            assertTrue(config.getSavedAccounts("netease").isEmpty());
            assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "new-fixture"));
            config.save();
            assertEquals(configBefore, Files.readString(configFile()));
            assertEquals("broken-fixture", Files.readString(accountsFile()));
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void unreadableUtf8ConfigurationRetainsExactBytesAndNeverOverwritesValidPrivateStorage() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            var store = new ClientAccountStore(accountsFile()); store.setCredential("qq", "existing-fixture", true);
            byte[] privateBefore = Files.readAllBytes(accountsFile());
            writeLegacy("{\"netease\":\"only-copy-fixture\"}", "");
            byte[] valid = Files.readAllBytes(configFile());
            byte[] corrupted = java.util.Arrays.copyOf(valid, valid.length + 2);
            corrupted[valid.length] = (byte) 0xc3; corrupted[valid.length + 1] = 0x28;
            Files.write(configFile(), corrupted);
            var config = new ClientConfigDefinition(accountsFile()); config.load();
            config.setSoundVolume(12); config.save();
            assertTrue(config.isConfigured());
            assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "replacement"));
            assertArrayEquals(corrupted, Files.readAllBytes(configFile()));
            assertArrayEquals(privateBefore, Files.readAllBytes(accountsFile()));
            Files.write(configFile(), valid); config.load();
            assertEquals("only-copy-fixture", config.getTuneWeaveCredential("netease"));
            assertEquals("existing-fixture", config.getTuneWeaveCredential("qq"));
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void configurationDirectoryReadFailureDoesNotDeleteUserFilesOrCreatePrivateStorage() throws Exception {
        Path previous = MusicHud.getConfigDirectory(); MusicHud.setConfigDirectory(configFile().getParent());
        try {
            Files.createDirectories(configFile());
            Path unrelated = configFile().resolve("user-note.txt"); Files.writeString(unrelated, "keep");
            var config = new ClientConfigDefinition(accountsFile()); config.load(); config.save();
            assertEquals("keep", Files.readString(unrelated));
            assertFalse(Files.exists(accountsFile()));
            assertTrue(config.getSavedAccounts("netease").isEmpty());
            assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "replacement"));
        } finally { MusicHud.setConfigDirectory(previous); }
    }

    @Test void readOnlyGettersNeverInitializeStorageOutsideTheLoadedInstance() {
        var config = new ClientConfigDefinition(accountsFile());
        assertTrue(config.getSavedAccounts("netease").isEmpty());
        assertEquals("", config.getTuneWeaveCredential("netease"));
        assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "fixture"));
        assertFalse(Files.exists(accountsFile().getParent()));
    }
}
