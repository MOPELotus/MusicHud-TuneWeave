package indi.mopelotus.musichud.neoforge;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.neoforgespi.language.MavenVersionAdapter;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoaderMetadataTest {
    private static Config metadata() throws Exception {
        String content = Files.readString(Path.of(System.getProperty("musichud.test.neoMetadata")));
        assertFalse(content.contains("${"), "Loader metadata contains an unresolved build placeholder");
        return new TomlParser().parse(content);
    }

    private static VersionRange dependencyRange(String modId) throws Exception {
        List<Config> dependencies = metadata().get("dependencies.musichud_tuneweave");
        List<Config> matching = dependencies.stream()
                .filter(config -> modId.equals(config.get("modId"))).toList();
        assertEquals(1, matching.size(), "Expected one declaration for " + modId);
        Config dependency = matching.getFirst();
        assertEquals("required", dependency.get("type"));
        String spec = dependency.get("versionRange");
        assertNotNull(spec);
        assertFalse(spec.isBlank());
        VersionRange range = MavenVersionAdapter.createFromVersionSpec(spec);
        assertTrue(range.hasRestrictions(), "Dependency must have a version restriction");
        assertNotNull(range.getRestrictions().getFirst().getLowerBound(), "Dependency must have a lower bound");
        return range;
    }

    @Test
    void loaderMetadataAcceptsTheVersionsUsedToBuildTheMod() throws Exception {
        for (String modId : List.of("neoforge", "minecraft")) {
            String version = System.getProperty("musichud.test." + modId + "Version");
            assertTrue(dependencyRange(modId).containsVersion(new DefaultArtifactVersion(version)),
                    () -> "Loader metadata rejects the configured " + modId + " " + version);
        }
    }

    @Test
    void neoforgeMinimumAcceptsTheSupportedBetaAndRejectsOlderVersions() throws Exception {
        VersionRange range = dependencyRange("neoforge");
        assertTrue(range.containsVersion(new DefaultArtifactVersion("21.9.16-beta")));
        assertTrue(range.containsVersion(new DefaultArtifactVersion("21.10.64")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("21.9.16-alpha")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("21.8.99")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("21.11.0")));
    }
    @Test
    void endpointAndModernUiRangesRejectOlderAndOutOfGroupVersions() throws Exception {
        VersionRange minecraft = dependencyRange("minecraft");
        for (String version : List.of("1.21.9", "1.21.10")) {
            assertTrue(minecraft.containsVersion(new DefaultArtifactVersion(version)));
            assertTrue(dependencyRange("modernui").containsVersion(
                    new DefaultArtifactVersion("3.12.0.5-build.4+mc" + version)));
        }
        for (String version : List.of("1.21.8", "1.21.11")) {
            assertFalse(minecraft.containsVersion(new DefaultArtifactVersion(version)));
        }
        assertFalse(dependencyRange("modernui").containsVersion(
                new DefaultArtifactVersion("3.12.0.5-build.3+mc1.21.10")));
    }

}
