package indi.mopelotus.musichud.platform.mod.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClientAccountStoreTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("api/client-accounts.json"); }

    @Test void legacyGsonRejectsRawControlsAndNonJsonEscapesWithoutChangingPrivateFiles() throws Exception {
        var store = new ClientAccountStore(file());
        store.loginAccount("netease", "fixture", "101", "safe", "");
        String valid = Files.readString(file());
        for (String nickname : List.of("\"raw\nline\"", "\"raw\tline\"", "\"escaped\\'apostrophe\"")) {
            String malformed = valid.replace("\"safe\"", nickname);
            Files.writeString(file(), malformed);
            assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
            assertEquals(malformed, Files.readString(file()));
        }
        Files.writeString(file(), valid.replace("\"safe\"", "\"escaped\\nline\""));
        assertEquals("escaped\nline", new ClientAccountStore(file()).accounts("netease").getFirst().nickname());
    }

    @Test void multipleAccountsSelectRotateMergeAndLogoutWithoutSelectingAnotherIdentity() throws Exception {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "first", true);
        store.profile("netease", "101", "First", "https://example.test/a");
        String firstId = store.accounts("netease").getFirst().id();
        store.setCredential("netease", "second", true);
        store.profile("netease", "202", "Second", "");
        String secondId = store.accounts("netease").getLast().id();
        store.setCredential("qq", "qq-account", true);
        assertEquals(2, store.accounts("netease").size());
        store.select("netease", firstId);
        store.setCredential("netease", "rotated", false);
        assertEquals("rotated", store.credential("netease"));
        assertEquals(firstId, store.accounts("netease").stream().filter(a -> a.selected()).findFirst().orElseThrow().id());
        store.select("netease", secondId);
        assertEquals("second", store.credential("netease"));
        store.setCredential("netease", "new-login-first", true);
        store.profile("netease", "101", "First updated", "");
        assertEquals(2, store.accounts("netease").size());
        assertEquals("new-login-first", store.credential("netease"));
        assertEquals(firstId, store.accounts("netease").stream().filter(a -> a.selected()).findFirst().orElseThrow().id());
        var reloaded = new ClientAccountStore(file());
        assertEquals(store.accounts("netease"), reloaded.accounts("netease"));
        assertEquals("new-login-first", reloaded.credential("netease"));
        reloaded.setCredential("netease", null, false);
        assertEquals("", reloaded.credential("netease"));
        assertEquals(List.of(secondId), reloaded.accounts("netease").stream().map(a -> a.id()).toList());
        assertFalse(reloaded.accounts("netease").getFirst().selected());
        assertEquals("qq-account", reloaded.credential("qq"));
        reloaded.select("netease", secondId);
        assertEquals("second", new ClientAccountStore(file()).credential("netease"));
    }

    @Test void metadataCannotExposeCredentialsAndNullProfileFieldsAreSafe() {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "sentinel-secret", true);
        store.profile("netease", null, null, null);
        assertFalse(store.accounts("netease").toString().contains("sentinel-secret"));
        assertEquals("", store.accounts("netease").getFirst().userId());
    }

    @Test void repeatedMigrationMergesWithoutOverwritingCurrentSelectionOrProfile() {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "current", true);
        store.profileData(JsonParser.parseString("{\"profile\":{\"nickname\":\"Current\"}}"));
        String selected = store.accounts("netease").getFirst().id();
        String legacy = "{\"netease\":\"old\",\"qq\":\"other\"}";
        store.migrate(legacy, "{\"profile\":{\"nickname\":\"Old\"}}");
        store.migrate(legacy, "{\"profile\":{\"nickname\":\"Old\"}}");
        assertEquals(2, store.accounts("netease").size());
        assertEquals(1, store.accounts("qq").size());
        assertEquals("current", store.credential("netease"));
        assertEquals(selected, store.accounts("netease").stream().filter(a -> a.selected()).findFirst().orElseThrow().id());
        assertEquals("Current", store.profileData().getAsJsonObject().getAsJsonObject("profile").get("nickname").getAsString());
    }

    @Test void migrationFillsNullProfileAndCopiesProfileData() {
        var store = new ClientAccountStore(file());
        store.profileData(null);
        store.migrate("{}", "{\"idlePlaySources\":[]}");
        JsonObject copy = store.profileData().getAsJsonObject();
        copy.addProperty("other", true);
        assertFalse(store.profileData().getAsJsonObject().has("other"));
    }

    @Test void invalidMutationDoesNotChangePersistedOrInMemoryState() throws Exception {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "original", true);
        byte[] before = Files.readAllBytes(file());
        assertThrows(IllegalArgumentException.class, () -> store.select("netease", "missing"));
        assertThrows(IllegalArgumentException.class, () -> store.setCredential("netease", "bad\ncredential", false));
        assertThrows(IllegalArgumentException.class, () -> store.setCredential("netease", "x".repeat(131073), true));
        assertThrows(IllegalArgumentException.class, () -> store.profileData(JsonParser.parseString("[]")));
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals("original", store.credential("netease"));
        assertEquals(1, store.accounts("netease").size());
    }

    @Test void migrationRejectsWholeInputBeforeWritingAnyAccount() throws Exception {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "original", true);
        byte[] before = Files.readAllBytes(file());
        for (String malformed : List.of("[]", "null", "{\"netease\":\"first\",\"qq\":7}",
                "{\"netease\":\"first\",\"unknown\":\"second\"}", "{\"netease\":\"first\",\"netease\":\"second\"}")) {
            assertThrows(RuntimeException.class, () -> store.migrate(malformed, ""));
            assertArrayEquals(before, Files.readAllBytes(file()));
            assertEquals("original", store.credential("netease"));
        }
        assertThrows(RuntimeException.class, () -> store.migrate("{\"qq\":\"first\"}", "[]"));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test void profileSchemaAllowsSupportedModelsAndRejectsClassLoadingOrMalformedTypes() throws Exception {
        var store = new ClientAccountStore(file());
        String valid = "{\"profile\":{\"nickname\":\"Fixture\",\"avatarUrl\":\"\",\"userId\":42,\"vipType\":11},"
                + "\"idlePlaySources\":[{\"id\":1,\"type\":\"indi.mopelotus.musichud.beans.music.Playlist\","
                + "\"mode\":\"RANDOM\",\"sourceReference\":\"netease:playlist:1\"}]}";
        store.profileData(JsonParser.parseString(valid));
        assertEquals(store.profileData(), new ClientAccountStore(file()).profileData());
        byte[] before = Files.readAllBytes(file());
        for (String malformed : List.of("{\"idlePlaySources\":true}", "{\"profile\":[]}",
                "{\"profile\":{\"userId\":1.1}}", "{\"profile\":{\"userId\":9223372036854775808}}",
                "{\"profile\":{\"nickname\":{}}}", "{\"profile\":{\"vipType\":{}}}",
                "{\"idlePlaySources\":[null]}",
                "{\"idlePlaySources\":[{\"id\":1,\"type\":\"java.lang.System\"}]}",
                "{\"idlePlaySources\":[{\"id\":1,\"type\":\"indi.mopelotus.musichud.beans.music.Playlist\",\"mode\":99}]}")) {
            assertThrows(RuntimeException.class, () -> store.profileData(JsonParser.parseString(malformed)));
            assertArrayEquals(before, Files.readAllBytes(file()));
        }
    }

    @Test void failedAtomicReplacementPreservesMemoryAndUnrelatedFiles() throws Exception {
        var store = new ClientAccountStore(file());
        store.setCredential("netease", "original", true);
        Files.delete(file()); Files.createDirectory(file());
        Path unrelated = file().resolve("user-note.txt"); Files.writeString(unrelated, "keep");
        assertThrows(IllegalStateException.class, () -> store.setCredential("netease", "new", false));
        assertEquals("original", store.credential("netease"));
        assertEquals("keep", Files.readString(unrelated));
        try (var children = Files.list(file().getParent())) { assertEquals(List.of(file()), children.toList()); }
    }

    @Test void uniqueTemporaryFilesNeverTouchExistingFilesAndUseOwnerPermissions() throws Exception {
        Files.createDirectories(file().getParent());
        Path unrelated = file().getParent().resolve(".client-accounts-user.tmp");
        Files.writeString(unrelated, "keep");
        var store = new ClientAccountStore(file()); store.setCredential("netease", "value", true);
        store.setCredential("netease", "rotated", false);
        assertEquals("keep", Files.readString(unrelated));
        try (var children = Files.list(file().getParent())) { assertEquals(2, children.count()); }
        if (file().getFileSystem().supportedFileAttributeViews().contains("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file()));
    }

    @Test void strictBoundedReaderRejectsCorruptionWithoutOverwritingFileOrLeakingItsContents() throws Exception {
        Files.createDirectories(file().getParent());
        String valid = "{\"version\":1,\"platforms\":{}}";
        for (String text : List.of("null", "[]", "{", valid + " {}", "{version:1,platforms:{}}",
                "{\"version\":1,\"version\":1,\"platforms\":{}}", "{\"version\":\"1\",\"platforms\":{}}",
                "{\"version\":2,\"platforms\":{}}", "{\"version\":1,\"platforms\":[]}",
                "{\"version\":1,\"platforms\":{\"unknown\":{}}}",
                "{\"version\":1,\"platforms\":{},\"profile\":[]}",
                "{\"version\":1,\"platforms\":{},\"profile\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}",
                "{\"version\":1,\"platforms\":{},\"unexpected\":\"sentinel-secret\"}")) {
            Files.writeString(file(), text);
            Exception error = assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("sentinel-secret"));
            assertEquals(text, Files.readString(file()));
        }
    }

    @Test void accountSchemaRejectsMalformedFieldsAndInvalidSelection() throws Exception {
        Files.createDirectories(file().getParent());
        for (String state : List.of("{\"active\":\"none\",\"accounts\":[]}",
                "{\"accounts\":{}}", "{\"accounts\":[{\"id\":\"a\",\"credential\":true}]}",
                "{\"accounts\":[{\"id\":\"../path\",\"credential\":\"test\"}]}",
                "{\"accounts\":[{\"id\":\"a\",\"credential\":\"test\"},{\"id\":\"a\",\"credential\":\"test2\"}]}",
                "{\"accounts\":[{\"id\":\"a\",\"credential\":\"test\",\"nickname\":{}}]}")) {
            String text = "{\"version\":1,\"platforms\":{\"netease\":" + state + "}}";
            Files.writeString(file(), text);
            assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
            assertEquals(text, Files.readString(file()));
        }
    }

    @Test void accountCountLimitRejectsAdditionalLoginWithoutLosingAccounts() {
        var store = new ClientAccountStore(file());
        for (int i = 0; i < 32; i++) store.setCredential("netease", "credential-" + i, true);
        assertThrows(IllegalArgumentException.class, () -> store.setCredential("netease", "overflow", true));
        assertEquals(32, new ClientAccountStore(file()).accounts("netease").size());
        assertEquals("credential-31", store.credential("netease"));
    }

    @Test void fullyValidatedLoginAtomicallyReusesIdentityAtAccountLimit() throws Exception {
        var store = new ClientAccountStore(file());
        for (int i = 0; i < 32; i++) store.loginAccount("netease", "credential-" + i, "user-" + i, "Name " + i, "");
        String firstId = store.accounts("netease").getFirst().id();
        store.loginAccount("netease", "relogin-first", "user-0", "Updated", "");
        assertEquals(32, store.accounts("netease").size());
        var selected = store.accounts("netease").stream().filter(a -> a.selected()).findFirst().orElseThrow();
        assertEquals(firstId, selected.id()); assertEquals("Updated", selected.nickname());
        assertEquals("relogin-first", store.credential("netease"));
        byte[] before = Files.readAllBytes(file());
        assertThrows(IllegalArgumentException.class, () -> store.loginAccount("netease", "overflow", "user-new", "New", ""));
        assertThrows(IllegalArgumentException.class, () -> store.loginAccount("netease", "", "user-0", "Changed", ""));
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals("relogin-first", new ClientAccountStore(file()).credential("netease"));
        assertEquals(selected, new ClientAccountStore(file()).accounts("netease").getFirst());
    }

    @Test void refusesOversizeAndMalformedUtf8Files() throws Exception {
        Files.createDirectories(file().getParent());
        try (var output = FileChannel.open(file(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            output.position(32L * 1024 * 1024); output.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
        Files.write(file(), new byte[]{(byte)0xc3, (byte)0x28});
        assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
    }

    @Test void refusesLinkedDestinationAndNeverChangesLinkTarget() throws Exception {
        if (!file().getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Files.createDirectories(file().getParent());
        Path target = directory.resolve("unrelated.json"); Files.writeString(target, "keep");
        var store = new ClientAccountStore(file());
        Files.createSymbolicLink(file(), target);
        assertThrows(IllegalStateException.class, () -> new ClientAccountStore(file()));
        assertThrows(IllegalStateException.class, () -> store.setCredential("netease", "new", true));
        assertEquals("keep", Files.readString(target));
        assertTrue(Files.isSymbolicLink(file()));
    }
}
