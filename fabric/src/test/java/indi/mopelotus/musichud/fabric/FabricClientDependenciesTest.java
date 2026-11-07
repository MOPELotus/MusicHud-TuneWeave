package indi.mopelotus.musichud.fabric;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FabricClientDependenciesTest {
    @Test void dedicatedServerDoesNotRequireTheClientUiHost() throws Exception {
        FabricClientDependencies.requireModernUi(false, null, VersionPredicate.parse(">=3.13.0.7"));
    }

    @Test void clientRejectsMissingOldAndMalformedVersionsBeforeUiInitialization() throws Exception {
        var required = VersionPredicate.parse(">=3.13.0.7");
        assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(true, null, required));
        for (String version : new String[]{"3.12.0", "3.13.0.6", "not-a-version"}) {
            assertThrows(IllegalStateException.class, () -> FabricClientDependencies.requireModernUi(true, Version.parse(version), required));
        }
        FabricClientDependencies.requireModernUi(true, Version.parse("3.13.0.9"), required);
    }

    @Test void packagedMetadataKeepsUiRequirementClientOnly() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(
                Path.of(System.getProperty("musichud.test.fabricMetadata")))).getAsJsonObject();
        assertFalse(metadata.getAsJsonObject("depends").has("modernui"));
        var loaderRange = VersionPredicate.parse(metadata.getAsJsonObject("depends").get("fabricloader").getAsString());
        assertFalse(loaderRange.test(Version.parse("0.19.3")), "Old MixinExtras cannot read Lumen's redirect annotations");
        assertTrue(loaderRange.test(Version.parse("0.20.1")));
        assertEquals("*", metadata.get("environment").getAsString());
        assertEquals(FabricClientDependencies.class.getName(), metadata.getAsJsonObject("entrypoints").getAsJsonArray("preLaunch").get(0).getAsString());
        String constraint = metadata.getAsJsonObject("custom").get("musichud_tuneweave_client_modernui").getAsString();
        assertNotNull(FabricClientDependencies.parseModernUiRequirement(constraint));
        for (String malformed : new String[]{">=???", ">=not-a-version", "", "*", null}) {
            assertThrows(net.fabricmc.loader.api.VersionParsingException.class, () -> FabricClientDependencies.parseModernUiRequirement(malformed));
        }
    }
}
