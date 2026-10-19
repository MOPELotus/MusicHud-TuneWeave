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

    private static Config dependency(String modId) throws Exception {
        List<Config> dependencies = metadata().get("dependencies.musichud_tuneweave");
        List<Config> matching = dependencies.stream()
                .filter(config -> modId.equals(config.get("modId"))).toList();
        assertEquals(1, matching.size(), "Expected one declaration for " + modId);
        return matching.getFirst();
    }

    private static VersionRange dependencyRange(String modId) throws Exception {
        Config dependency = dependency(modId);
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
    void loaderMetadataDeclaresUpstreamMusicHudIncompatibleForAllVersions() throws Exception {
        Config upstream = dependency("music_hud");
        assertEquals("incompatible", upstream.get("type"));
        assertEquals("BOTH", upstream.get("side"));
        assertEquals("NONE", upstream.get("ordering"));
        assertEquals("MusicHud TuneWeave cannot run with upstream MusicHud (music_hud). Remove upstream MusicHud.",
                upstream.get("reason"));
        assertEquals("(,)", upstream.get("versionRange"));
        VersionRange range = MavenVersionAdapter.createFromVersionSpec(upstream.get("versionRange"));
        for (String version : List.of("0", "1.3.1+26.3", "2.0.0-alpha", "999.0")) {
            assertTrue(range.containsVersion(new DefaultArtifactVersion(version)),
                    () -> "Upstream incompatibility must apply to " + version);
        }
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
    void neoforgeRangeAcceptsTheSupportedVersionAndRejectsOtherMinecraftLines() throws Exception {
        VersionRange range = dependencyRange("neoforge");
        assertTrue(range.containsVersion(new DefaultArtifactVersion("21.11.45")));
        assertTrue(range.containsVersion(new DefaultArtifactVersion("21.11.46")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("21.11.44")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("21.12.1")));
    }
}
