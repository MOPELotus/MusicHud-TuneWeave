package indi.mopelotus.musichud.fabric;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FabricDependencyMetadataTest {
    private static VersionPredicate apiRequirement() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(
                Path.of(System.getProperty("musichud.test.fabricMetadata")))).getAsJsonObject();
        return VersionPredicate.parse(metadata.getAsJsonObject("depends").get("fabric-api").getAsString());
    }

    @Test
    void minecraftVersionIsRestrictedToTheAdaptedRelease() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(
                Path.of(System.getProperty("musichud.test.fabricMetadata")))).getAsJsonObject();
        var requirement = VersionPredicate.parse(metadata.getAsJsonObject("depends").get("minecraft").getAsString());
        assertTrue(requirement.test(Version.parse("26.3")));
        assertTrue(requirement.test(Version.parse("26.3.1")));
        for (String version : new String[]{"26.2", "26.3-rc.3", "26.4", "27.1", "not-a-version"}) {
            assertFalse(requirement.test(Version.parse(version)), version);
        }
    }

    @Test
    void configuredApiSatisfiesManifestAndSuppliesRequiredRenderingInterface() throws Exception {
        assertTrue(apiRequirement().test(Version.parse(System.getProperty("musichud.test.fabricApiVersion"))));
        assertTrue(apiRequirement().test(Version.parse("0.160.5+26.3")));
        assertNotNull(Class.forName("net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector",
                false, getClass().getClassLoader()));
    }

    @Test
    void unsupportedAndMalformedApiVersionsCannotPassTheManifestGate() throws Exception {
        var requirement = apiRequirement();
        for (String version : new String[]{"0.160.0+26.2", "0.160.4+26.3", "0.160.5-beta.1+26.3", "not-a-version"}) {
            assertFalse(requirement.test(Version.parse(version)), version);
        }
    }
}
