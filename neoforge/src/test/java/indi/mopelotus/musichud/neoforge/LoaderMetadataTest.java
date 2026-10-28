package indi.mopelotus.musichud.neoforge;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.fml.i18n.FMLTranslations;
import net.neoforged.fml.i18n.I18nManager;
import net.neoforged.neoforgespi.language.MavenVersionAdapter;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
        assertNull(upstream.get("versionRange"), "Use the loader default for all upstream versions");
        VersionRange range = IModInfo.UNBOUNDED;
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
                    new DefaultArtifactVersion("3.13.0.9+mc" + version)));
        }
        for (String version : List.of("1.21.8", "1.21.11")) {
            assertFalse(minecraft.containsVersion(new DefaultArtifactVersion(version)));
        }
        assertFalse(dependencyRange("modernui").containsVersion(
                new DefaultArtifactVersion("3.13.0.8+mc1.21.10")));
    }

    @Test
    void modernUiRequirementIsClientOnlyAndRejectsLegacyVersionsWithBilingualHelp() throws Exception {
        Config modernUi = dependency("modernui");
        assertEquals("CLIENT", modernUi.get("side"), "Dedicated servers must not require a client UI host");
        VersionRange range = dependencyRange("modernui");
        for (String old : List.of("3.12.0", "3.12.0.5-build.4+mc1.21.10", "3.13.0.8")) {
            assertFalse(range.containsVersion(new DefaultArtifactVersion(old)), old);
        }
        assertTrue(range.containsVersion(new DefaultArtifactVersion("3.13.0.9")));
        assertTrue(range.containsVersion(new DefaultArtifactVersion("3.13.1")));
        String reason = modernUi.get("reason");
        assertNotNull(reason);
        assertTrue(reason.contains("ModernUI is missing or too old"));
        assertTrue(reason.contains("未安装 ModernUI 或版本过低"));
        assertTrue(reason.contains("3.13.0.9 or newer for your Minecraft version"));
        assertTrue(reason.contains("与当前 Minecraft 版本匹配"));
        assertEquals("https://github.com/MOPELotus/ModernUI-MC/releases", reason.lines().toList().getLast());
    }

    @Test
    void realLoaderFormatterShowsLiteralBilingualReasonForMissingAndLegacyHosts() throws Exception {
        String reason = dependency("modernui").get("reason");
        VersionRange range = dependencyRange("modernui");
        for (String locale : List.of("en_us", "zh_cn")) {
            String pattern = I18nManager.loadTranslations(locale).get("fml.modloadingissue.missingdependency");
            assertNotNull(pattern, "The actual loader must have its native dependency-error pattern");
            for (String installed : List.of("null", "3.12.0.5-build.4+mc1.21.10")) {
                // This is the same argument order used by ModSorter.buildErrorMessages.
                // 'null' is the loader's real missing-artifact sentinel, not a fake version.
                String rendered = FMLTranslations.parseFormat(pattern, "modernui", "musichud_tuneweave", range,
                        new DefaultArtifactVersion(installed), Optional.of(reason));
                assertTrue(rendered.contains(reason), locale + " native error must retain both languages and the URL");
                assertTrue(rendered.contains("musichud_tuneweave"));
                assertFalse(rendered.contains("{4,optional"), "Reason must be formatted, not an unresolved message key");
                if (!installed.equals("null")) assertTrue(rendered.contains(installed));
            }
        }
    }

}
