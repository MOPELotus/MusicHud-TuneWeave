package indi.mopelotus.musichud.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class CompanionReleaseInstallerTest {
    @TempDir Path root;
    private static final URI URL = URI.create("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/download/v1/Companion-0.2.0-windows-x64.zip");

    @Test void installsCompleteImageWithoutTouchingExistingFilesOrVersions() throws Exception {
        Files.createDirectories(root.resolve("versions/active/Companion"));
        Path active = Files.writeString(root.resolve("versions/active/Companion/Companion.exe"), "active");
        Path unrelated = Files.writeString(root.resolve("user-file"), "keep");
        byte[] zip = image(Map.of("Companion/Companion.exe", "MZ", "Companion/app/app.cfg", "cfg", "Companion/runtime/release", "runtime"));
        var installer = installer(zip);
        var result = installer.install(root, descriptor(zip), new AtomicBoolean(), null);
        assertEquals("0.2.0", result.version());
        assertEquals("MZ", Files.readString(result.executable()));
        assertEquals("active", Files.readString(active));
        assertEquals("keep", Files.readString(unrelated));
        assertNotEquals(active, result.executable());
        assertNoStaging();
    }

    @Test void hashMismatchAndTruncatedDownloadsLeaveNoPartialInstallation() throws Exception {
        byte[] zip = image(Map.of("Companion/Companion.exe", "MZ"));
        var descriptor = descriptor(zip);
        assertThrows(IOException.class, () -> installer(zip).install(root,
                new CompanionReleaseInstaller.Descriptor(descriptor.version(), URL, "0".repeat(64), zip.length, descriptor.entryPoint()), new AtomicBoolean(), null));
        assertThrows(IOException.class, () -> installer(Arrays.copyOf(zip, zip.length - 1)).install(root, descriptor, new AtomicBoolean(), null));
        assertNoStaging();
        try (var entries = Files.list(root.resolve("versions"))) { assertEquals(0, entries.count()); }
    }

    @Test void cancellationDuringDownloadPreservesOtherFiles() throws Exception {
        Files.writeString(root.resolve("keep"), "keep");
        byte[] zip = image(Map.of("Companion/Companion.exe", "MZ"));
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> installer(zip).install(root, descriptor(zip), cancelled, (done, total) -> cancelled.set(true)));
        assertEquals("keep", Files.readString(root.resolve("keep")));
        assertNoStaging();
    }

    @Test void rejectsTraversalAbsoluteWindowsAliasesAndDuplicateNames() throws Exception {
        for (String path : List.of("../escaped", "/absolute", "Companion/../../escaped", "Companion/../escaped",
                "Companion/C:evil", "Companion\\evil", "Companion/CON.txt", "Companion/trailing.", "Companion//evil", "elsewhere/file")) {
            byte[] zip = image(Map.of(path, "evil"));
            assertThrows(IOException.class, () -> installer(zip).install(root, descriptor(zip), new AtomicBoolean(), null), path);
            assertNoStaging();
        }
        byte[] duplicates = image(Map.of("Companion/App.txt", "one", "Companion/app.txt", "two"));
        assertThrows(IOException.class, () -> installer(duplicates).install(root, descriptor(duplicates), new AtomicBoolean(), null));
        assertFalse(Files.exists(root.getParent().resolve("escaped")));
    }

    @Test void rejectsSymlinkManagedRootWithoutWritingThroughIt() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        Path symlink = root.resolve("link");
        try { Files.createSymbolicLink(symlink, actual); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { return; }
        byte[] zip = image(Map.of("Companion/Companion.exe", "MZ"));
        assertThrows(IOException.class, () -> installer(zip).install(symlink, descriptor(zip), new AtomicBoolean(), null));
        assertFalse(Files.exists(actual.resolve("versions")));
    }

    @Test void requiresCompleteAppImage() throws Exception {
        byte[] zip = image(Map.of("Companion/Companion.exe", "MZ"));
        assertThrows(IOException.class, () -> installer(zip).install(root, descriptor(zip), new AtomicBoolean(), null));
        assertNoStaging();
    }

    @Test void boundsEntryCount() throws Exception {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 20_001; i++) entries.put("Companion/file" + i, "");
        byte[] zip = image(entries);
        assertThrows(IOException.class, () -> installer(zip).install(root, descriptor(zip), new AtomicBoolean(), null));
        assertNoStaging();
    }

    @Test void validatesOfficialManifestIdentityAndBoundaries() throws Exception {
        String valid = "{\"schemaVersion\":1,\"version\":\"0.2.0\",\"protocolVersion\":1,\"platforms\":{\"windows-x64\":{\"url\":\"" + URL + "\",\"sha256\":\"" + "a".repeat(64) + "\",\"size\":123,\"entryPoint\":\"Companion/Companion.exe\"}}}";
        assertEquals("0.2.0", CompanionReleaseInstaller.parseManifest(valid).version());
        for (String invalid : List.of("{}", "[]", "null", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2"), valid.replace("0.2.0", "../../evil"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"), valid.replace("\"size\":123", "\"size\":123.5"),
                valid.replace("github.com", "github.com.evil.org"), valid.replace("https:", "http:"),
                valid.replace("MusicHud-TuneWeave/releases", "other/releases"), valid.replace("\"size\":123", "\"size\":536870913"),
                valid.replace("\"size\":123", "\"size\":0"), valid.replace("Companion/Companion.exe", "../evil.exe"))) {
            assertThrows(IOException.class, () -> CompanionReleaseInstaller.parseManifest(invalid), invalid);
        }
    }

    @Test void boundsFetchedManifest() {
        var installer = new CompanionReleaseInstaller((uri, cancelled) -> new ByteArrayInputStream(new byte[65_537]));
        assertThrows(IOException.class, () -> installer.fetchLatest(new AtomicBoolean()));
    }

    @Test void missingLatestManifestFallsBackToNewestPublishedCatalog() throws Exception {
        URI old = catalog("v-old"), newest = catalog("v-new");
        String releases = "[" + release(false, false, "2026-10-01T00:00:00Z", old) + ","
                + release(false, false, "2026-11-01T00:00:00Z", newest) + ","
                + release(true, false, "2026-11-03T00:00:00Z", catalog("draft")) + ","
                + release(false, true, "2026-11-04T00:00:00Z", catalog("preview")) + ","
                + release(false, false, null, catalog("unpublished")) + ","
                + "{\"draft\":false,\"prerelease\":false,\"published_at\":\"2026-11-05T00:00:00Z\",\"assets\":[]}]";
        List<URI> calls = new ArrayList<>();
        var installer = new CompanionReleaseInstaller((uri, cancelled) -> {
            calls.add(uri);
            if (uri.equals(CompanionReleaseInstaller.MANIFEST)) throw new CompanionReleaseInstaller.HttpFailure(404);
            if (uri.equals(CompanionReleaseInstaller.RELEASES)) return stream(releases);
            assertEquals(newest, uri);
            return stream(manifest());
        });
        assertEquals("0.2.0", installer.fetchLatest(new AtomicBoolean()).version());
        assertEquals(List.of(CompanionReleaseInstaller.MANIFEST, CompanionReleaseInstaller.RELEASES, newest), calls);
    }

    @Test void onlyHttp404TriggersReleaseCatalogFallback() {
        for (IOException failure : List.of(new CompanionReleaseInstaller.HttpFailure(403), new CompanionReleaseInstaller.HttpFailure(500), new IOException("HTTP 404 text"))) {
            List<URI> calls = new ArrayList<>();
            var installer = new CompanionReleaseInstaller((uri, cancelled) -> { calls.add(uri); throw failure; });
            assertSame(failure, assertThrows(IOException.class, () -> installer.fetchLatest(new AtomicBoolean())));
            assertEquals(List.of(CompanionReleaseInstaller.MANIFEST), calls);
        }
    }

    @Test void releaseCatalogRejectsForgedUrlsAndMalformedFieldTypes() {
        String good = release(false, false, "2026-11-01T00:00:00Z", catalog("v1"));
        for (String invalid : List.of("{}", "null", "[null]", "[{}]", "[" + good.replace("\"draft\":false", "\"draft\":\"false\"") + "]",
                "[" + good.replace("2026-11-01T00:00:00Z", "yesterday") + "]", "[" + good.replace("\"assets\":[", "\"assets\":{\"oops\":[") + "]",
                "[" + good.replace("github.com", "github.com.evil.invalid") + "]", "[" + good.replace("https:", "http:") + "]",
                "[" + good.replace("MusicHud-TuneWeave/releases", "other/releases") + "]", "[" + good.replace("/download/v1/", "/download/../") + "]",
                "[" + good.replace("/download/v1/", "/download/%2e%2e/") + "]", "[" + good.replace(".json\"", ".json?evil=1\"") + "]",
                "[" + good.replace("\"name\":\"companion-latest.json\"", "\"name\":42") + "]")) {
            assertThrows(IOException.class, () -> CompanionReleaseInstaller.findCatalog(invalid), invalid);
        }
        assertThrows(IOException.class, () -> CompanionReleaseInstaller.findCatalog("[" + String.join(",", Collections.nCopies(21, good)) + "]"));
        assertThrows(IOException.class, () -> CompanionReleaseInstaller.findCatalog("[" + release(true, false, "2026-11-01T00:00:00Z", catalog("draft")) + "]"));
        assertThrows(IOException.class, () -> CompanionReleaseInstaller.findCatalog("[]"));
    }

    @Test void boundsFallbackCatalogAndKeepsManifestValidation() {
        var oversized = new CompanionReleaseInstaller((uri, cancelled) -> {
            if (uri.equals(CompanionReleaseInstaller.MANIFEST)) throw new CompanionReleaseInstaller.HttpFailure(404);
            return new ByteArrayInputStream(new byte[2 * 1024 * 1024 + 1]);
        });
        assertThrows(IOException.class, () -> oversized.fetchLatest(new AtomicBoolean()));
        var invalidManifest = new CompanionReleaseInstaller((uri, cancelled) -> {
            if (uri.equals(CompanionReleaseInstaller.MANIFEST)) throw new CompanionReleaseInstaller.HttpFailure(404);
            if (uri.equals(CompanionReleaseInstaller.RELEASES)) return stream("[" + release(false, false, "2026-11-01T00:00:00Z", catalog("v1")) + "]");
            return stream(manifest().replace("github.com", "evil.invalid"));
        });
        assertThrows(IOException.class, () -> invalidManifest.fetchLatest(new AtomicBoolean()));
    }

    private static URI catalog(String tag) { return URI.create("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/download/" + tag + "/companion-latest.json"); }
    private static String release(boolean draft, boolean prerelease, String published, URI catalog) {
        return "{\"draft\":" + draft + ",\"prerelease\":" + prerelease + ",\"published_at\":" + (published == null ? "null" : "\"" + published + "\"")
                + ",\"assets\":[{\"name\":\"companion-latest.json\",\"browser_download_url\":\"" + catalog + "\"}]}";
    }
    private static String manifest() {
        return "{\"schemaVersion\":1,\"version\":\"0.2.0\",\"protocolVersion\":1,\"platforms\":{\"windows-x64\":{\"url\":\"" + URL + "\",\"sha256\":\"" + "a".repeat(64) + "\",\"size\":123,\"entryPoint\":\"Companion/Companion.exe\"}}}";
    }
    private static InputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private CompanionReleaseInstaller installer(byte[] bytes) {
        return new CompanionReleaseInstaller((uri, cancelled) -> new ByteArrayInputStream(bytes));
    }
    private CompanionReleaseInstaller.Descriptor descriptor(byte[] bytes) throws Exception {
        return new CompanionReleaseInstaller.Descriptor("0.2.0", URL, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length, "Companion/Companion.exe");
    }
    private byte[] image(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private void assertNoStaging() throws IOException {
        Path versions = root.resolve("versions");
        if (Files.exists(versions)) try (var entries = Files.list(versions)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".install-")));
        }
    }
}
