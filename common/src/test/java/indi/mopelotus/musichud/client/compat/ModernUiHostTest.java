package indi.mopelotus.musichud.client.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModernUiHostTest {
    @Test void lumenBothFontEditionsUseTheSameAbi() {
        for (String name : new String[]{"ModernUI Lumen", "ModernUI Lumen (MiSans)"})
            assertTrue(ModernUiHost.accepts(name, "3.13.0.11", "https://github.com/MOPELotus/ModernUI-MC-Lumen"));
    }
    @Test void rejectsOtherForksAndUnverifiedVersionsWithActionableUpdateInstructions() {
        for (String name : new String[]{null, "Modern UI (mVUS)", "Modern UI", "ModernUI Lumen"}) {
            for (String version : new String[]{null, "", "3.12.0", "3.13.0.9", "3.13.0.999", "3.13.0.11-build.1", "3.13.0.11"}) {
                var error = assertThrows(IllegalStateException.class,
                        () -> ModernUiHost.accepts(name, version, "https://example.org/other-fork"));
                assertTrue(error.getMessage().contains("版本过低"));
                assertTrue(error.getMessage().contains(ModernUiHost.DOWNLOAD));
            }
        }
    }
    @Test void previousLumenWithoutRequiredUpstreamFixesIsRejected() {
        assertThrows(IllegalStateException.class, () -> ModernUiHost.accepts(
                "ModernUI Lumen", "3.13.0.10", "https://github.com/MOPELotus/ModernUI-MC-Lumen"));
    }
    @Test void officialAbiIsAllowedOnlyOnSupportedBranches() {
        assertFalse(ModernUiHost.accepts("Modern UI", "3.13.0.1", "https://github.com/BloCamLimb/ModernUI-MC"));
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    private java.net.URL jar(String entry, String text) throws Exception {
        var path = temp.resolve(java.util.UUID.randomUUID() + ".jar");
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry));
            zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new java.net.URL("jar:" + path.toUri() + "!/");
    }

    @Test void parsesBothLoadersWithoutLoadingUiClasses() throws Exception {
        assertTrue(ModernUiHost.inspect(jar("fabric.mod.json", """
                {"id":"modernui","name":"ModernUI Lumen","version":"3.13.0.11",
                 "contact":{"sources":"https://github.com/MOPELotus/ModernUI-MC-Lumen"}}
                """)));
        assertTrue(ModernUiHost.inspect(jar("META-INF/neoforge.mods.toml", """
                [[mods]]
                    modId="modernui"
                    version="3.13.0.11"
                    displayName="ModernUI Lumen (MiSans)"
                    displayURL="https://github.com/MOPELotus/ModernUI-MC-Lumen"
                """)));
    }

    @Test void malformedMissingAndOversizedMetadataFailWithDownloadInstructions() throws Exception {
        for (String content : new String[]{"", "{", "{}", "[]", "null", " ".repeat(65537)}) {
            var url = jar("fabric.mod.json", content);
            var failure = assertThrows(IllegalStateException.class, () -> ModernUiHost.inspect(url));
            assertTrue(failure.getMessage().contains(ModernUiHost.DOWNLOAD));
        }
        var missing = jar("unrelated", "no metadata");
        assertThrows(IllegalStateException.class, () -> ModernUiHost.inspect(missing));
    }
}
