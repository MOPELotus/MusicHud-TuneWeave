package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClientAccountDirectoryChangeTest {
    @TempDir Path directory;
    private Path previousConfig;
    private Path accountFile(String folder) { return directory.resolve(folder).resolve("client-accounts.json"); }
    private Path configFile() { return directory.resolve("config").resolve(ProjectIdentity.CONFIG_PREFIX + "-client.toml"); }

    @BeforeEach void isolateConfiguration() {
        previousConfig = MusicHud.getConfigDirectory();
        MusicHud.setConfigDirectory(directory.resolve("config"));
    }
    @AfterEach void restoreConfiguration() { MusicHud.setConfigDirectory(previousConfig); }

    @Test void firstCustomInstallationWritesCurrentApiDirectoryAndReloadsThere() {
        Path initial = accountFile("default"); Path selected = accountFile("custom-api");
        var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        assertFalse(Files.exists(initial));
        current.set(selected);
        config.storeLoginAccount("netease", "fixture-current", "42", "Fixture", "");
        assertFalse(Files.exists(initial));
        assertEquals("fixture-current", new ClientAccountStore(selected).credential("netease"));
        var reloaded = new ClientConfigDefinition(selected); reloaded.load();
        assertEquals("fixture-current", reloaded.getTuneWeaveCredential("netease"));
        assertEquals("42", reloaded.getSavedAccounts("netease").getFirst().userId());
    }

    @Test void existingTargetOfAnyKindIsNeverAdoptedOrOverwritten() throws Exception {
        Path initial = accountFile("default"); var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        for (String text : List.of("unrelated-user-file", "{\"version\":1,\"platforms\":{}}")) {
            Path target = accountFile("target"); Files.createDirectories(target.getParent()); Files.writeString(target, text);
            current.set(target);
            var error = assertThrows(IllegalStateException.class,
                    () -> config.storeLoginAccount("netease", "new-fixture", "42", "Fixture", ""));
            assertEquals("API directory changed; move the account file and restart", error.getMessage());
            assertEquals(text, Files.readString(target));
            assertFalse(Files.exists(initial));
        }
        Path targetDirectory = accountFile("target-directory"); Files.createDirectories(targetDirectory);
        Path note = targetDirectory.resolve("user-note.txt"); Files.writeString(note, "keep"); current.set(targetDirectory);
        assertThrows(IllegalStateException.class, () -> config.setTuneWeaveCredential("netease", "fixture"));
        assertEquals("keep", Files.readString(note));
        assertFalse(Files.exists(initial));
    }

    @Test void danglingTargetSymlinkIsNotTreatedAsAnAbsentFile() throws Exception {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Path initial = accountFile("default"); Path target = accountFile("target");
        var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        Files.createDirectories(target.getParent()); Path missing = directory.resolve("user-missing.json");
        Files.createSymbolicLink(target, missing); current.set(target);
        assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "fixture"));
        assertTrue(Files.isSymbolicLink(target)); assertFalse(Files.exists(missing)); assertFalse(Files.exists(initial));
    }

    @Test void everyMutationRejectsMovedDirectoryWhenOldAccountDataExists() throws Exception {
        Path initial = accountFile("original"); Path target = accountFile("new-api");
        var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        config.storeLoginAccount("netease", "existing-fixture", "42", "Fixture", "");
        String id = config.getSavedAccounts("netease").getFirst().id();
        byte[] original = Files.readAllBytes(initial); current.set(target);
        for (Runnable mutation : List.<Runnable>of(
                () -> config.setClientAccountConfig(null),
                () -> config.setTuneWeaveCredential("netease", "rotated-fixture"),
                () -> config.clearTuneWeaveCredential("netease"),
                () -> config.selectSavedAccount("netease", id),
                () -> config.storeLoginCredential("netease", "another-fixture"),
                () -> config.storeLoginAccount("netease", "another-fixture", "99", "Another", ""),
                () -> config.storeAccountProfile("netease", "42", "Changed", ""))) {
            assertThrows(IllegalStateException.class, mutation::run);
            assertArrayEquals(original, Files.readAllBytes(initial)); assertFalse(Files.exists(target));
            assertEquals("existing-fixture", config.getTuneWeaveCredential("netease"));
        }
    }

    @Test void cachedGettersDoNotResolveChangedPathsOrReadDiskAndMissingSourceDoesNotPermitRebinding() throws Exception {
        Path initial = accountFile("original"); Path target = accountFile("new-api");
        var current = new AtomicReference<>(initial); var resolutions = new AtomicInteger();
        var config = new ClientConfigDefinition(() -> { resolutions.incrementAndGet(); return current.get(); }); config.load();
        config.storeLoginAccount("netease", "cached-fixture", "42", "Fixture", "");
        current.set(target); int before = resolutions.get();
        Files.delete(initial); Files.createDirectory(initial);
        assertEquals("cached-fixture", config.getTuneWeaveCredential("netease"));
        assertEquals(1, config.getSavedAccounts("netease").size());
        assertEquals(before, resolutions.get());
        assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "new-fixture"));
        assertTrue(Files.isDirectory(initial)); assertFalse(Files.exists(target));
    }

    @Test void persistedOrExternallyCreatedEmptySourceIsStillPreserved() throws Exception {
        Path initial = accountFile("default"); Path target = accountFile("new-api");
        var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        Files.createDirectories(initial.getParent()); Files.writeString(initial, "{\"version\":1,\"platforms\":{}}");
        byte[] before = Files.readAllBytes(initial); current.set(target);
        assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "fixture"));
        assertArrayEquals(before, Files.readAllBytes(initial)); assertFalse(Files.exists(target));
    }

    @Test void rapidEmptyDirectoryChangesAndFailedLoginCannotWriteAnEarlierPath() {
        Path initial = accountFile("default"); Path intermediate = accountFile("intermediate"); Path latest = accountFile("latest");
        var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load();
        current.set(intermediate);
        assertThrows(IllegalArgumentException.class, () -> config.storeLoginAccount("netease", "", "42", "Fixture", ""));
        current.set(latest);
        config.storeLoginAccount("netease", "latest-fixture", "42", "Fixture", "");
        assertFalse(Files.exists(initial)); assertFalse(Files.exists(intermediate));
        assertEquals("latest-fixture", new ClientAccountStore(latest).credential("netease"));
    }

    @Test void pendingLegacyMigrationTakesPrecedenceAndNeverErasesTheOnlyOldCredentialCopy() throws Exception {
        Path initial = accountFile("default"); Path target = accountFile("custom-api");
        SimpleTomlConfig.write(configFile(), List.of(new SimpleTomlConfig.Entry("tuneWeaveCredentials", "", "{malformed-fixture")));
        byte[] original = Files.readAllBytes(configFile()); var current = new AtomicReference<>(initial);
        var config = new ClientConfigDefinition(current::get); config.load(); current.set(target);
        var error = assertThrows(IllegalStateException.class, () -> config.storeLoginCredential("netease", "new-fixture"));
        assertEquals("Private account storage is unavailable", error.getMessage());
        config.save(); assertArrayEquals(original, Files.readAllBytes(configFile()));
        assertFalse(Files.exists(initial)); assertFalse(Files.exists(target));
    }
}
