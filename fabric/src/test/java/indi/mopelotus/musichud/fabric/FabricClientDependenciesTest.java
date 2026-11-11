package indi.mopelotus.musichud.fabric;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FabricClientDependenciesTest {
    private static String download() {
        return "cf".equals(System.getProperty("musichud.test.distribution"))
                ? "https://www.curseforge.com/minecraft/mc-mods/modernui-lumen"
                : "https://github.com/MOPELotus/ModernUI-MC-Lumen/releases/tag/v3.13.0.12";
    }
    @Test void dedicatedServerDoesNotRequireTheClientUiHost() throws Exception {
        FabricClientDependencies.requireModernUi(false, null, VersionPredicate.parse(">=3.13.0.12"));
    }

    @Test void clientRejectsMissingOldAndMalformedVersionsBeforeUiInitialization() throws Exception {
        var required = VersionPredicate.parse(">=3.13.0.12");
        var failure = assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(true, null, required));
        String download = "cf".equals(System.getProperty("musichud.test.distribution"))
                ? "https://www.curseforge.com/minecraft/mc-mods/modernui-lumen"
                : "https://github.com/MOPELotus/ModernUI-MC-Lumen/releases/tag/v3.13.0.12";
        assertTrue(failure.getMessage().contains(download));
        for (String version : new String[]{"3.12.0", "3.12.0.5-build.4+mc1.21.10", "3.12.0.5-build.4+mc1.21.11", "3.13.0.9", "3.13.0.11", "not-a-version"}) {
            assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(true, Version.parse(version), required));
        }
        FabricClientDependencies.requireModernUi(true, Version.parse("3.13.0.12"), required);
        FabricClientDependencies.requireModernUi(true, Version.parse("3.13.0.12"), required);
    }

    @Test void oldVersionExplainsRequiredVersionAndDownloadInBothLanguages() throws Exception {
        String installed = "3.12.0.5-build.4+mc1.21.10";
        var error = assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(
                true, Version.parse(installed), VersionPredicate.parse(">=3.13.0.12")));
        String message = error.getMessage();
        assertTrue(message.contains("Modern UI 版本过低"));
        assertTrue(message.contains("Modern UI version is too old"));
        assertTrue(message.contains("需要 / Required: ModernUI >=3.13.0.12"));
        assertTrue(message.contains("当前安装 / Installed: " + installed));
        assertTrue(message.contains("请使用适配当前 Minecraft 版本的新版 ModernUI"));
        assertTrue(message.contains("Please use an updated ModernUI build for your Minecraft version."));
        assertTrue(message.contains(download()));
    }

    @Test void missingHostIsDistinguishedFromAnOldVersion() throws Exception {
        var error = assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(
                true, null, VersionPredicate.parse(">=3.13.0.12")));
        assertTrue(error.getMessage().contains("未安装 ModernUI / ModernUI is missing"));
        assertTrue(error.getMessage().contains("当前安装 / Installed: 未安装 / missing"));
        assertTrue(error.getMessage().contains(download()));
    }

    @Test void packagedMetadataKeepsUiRequirementClientOnly() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(
                Path.of(System.getProperty("musichud.test.fabricMetadata")))).getAsJsonObject();
        assertFalse(metadata.getAsJsonObject("depends").has("modernui"));
        var loaderRange = VersionPredicate.parse(metadata.getAsJsonObject("depends").get("fabricloader").getAsString());
        assertFalse(loaderRange.test(Version.parse("0.14.25")), "Loader requirement must match the corresponding Lumen artifact");
        assertTrue(loaderRange.test(Version.parse("0.15.0")));
        assertEquals("*", metadata.get("environment").getAsString());
        assertEquals(FabricClientDependencies.class.getName(), metadata.getAsJsonObject("entrypoints").getAsJsonArray("preLaunch").get(0).getAsString());
        String constraint = metadata.getAsJsonObject("custom").get("musichud_tuneweave_client_modernui").getAsString();
        assertNotNull(FabricClientDependencies.parseModernUiRequirement(constraint));
        for (String malformed : new String[]{">=???", ">=not-a-version", "", "*", null}) {
            assertThrows(net.fabricmc.loader.api.VersionParsingException.class, () -> FabricClientDependencies.parseModernUiRequirement(malformed));
        }
    }
}
