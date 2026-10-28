package indi.mopelotus.musichud.client.update;

import indi.mopelotus.musichud.utils.ReleaseVersion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCatalogTest {
    private static final String HASH = "a".repeat(64);
    private static ClientUpdateCatalog.Installed installed(String edition) {
        return new ClientUpdateCatalog.Installed(edition.equals("cf") ? "1.3.0-cf.1+26.3" : "1.3.0+26.3", edition, "fabric", "26.3");
    }
    private static String catalog(String edition) {
        String version = "1.4.0-beta.1" + (edition.equals("cf") ? "-cf.1" : "") + "+26.3";
        return """
                {"schema":1,"repository":"MOPELotus/MusicHud-TuneWeave","tag":"v1.4.0-beta.1","distribution":"%s",
                "artifacts":[{"version":"%s","distribution":"%s","loader":"fabric","minecraft":["26.3"],
                "file":"musichud-tuneweave-fabric-%s.jar","sha256":"%s","size":123}]}
                """.formatted(edition, version, edition, version, HASH);
    }
    @Test void eachEditionIncludesPrereleasesWithoutCrossingLoaderOrGame() {
        for (String edition : List.of("standard", "cf")) {
            assertTrue(ClientUpdateCatalog.select(catalog(edition), "v1.4.0-beta.1", installed(edition), true, "notes").isPresent());
            assertTrue(ClientUpdateCatalog.select(catalog(edition), "v1.4.0-beta.1", installed(edition), false, "notes").isEmpty());
            assertTrue(ClientUpdateCatalog.select(catalog(edition).replace("26.3\"]", "26.2\"]"), "v1.4.0-beta.1", installed(edition), true, "").isEmpty());
        }
        assertThrows(RuntimeException.class, () -> ClientUpdateCatalog.select(catalog("cf"), "v1.4.0-beta.1", installed("standard"), true, ""));
        var neo = new ClientUpdateCatalog.Installed("1.3.0+26.3", "standard", "neoforge", "26.3");
        assertTrue(ClientUpdateCatalog.select(catalog("standard"), "v1.4.0-beta.1", neo, true, "").isEmpty());
    }
    @Test void malformedCatalogsFailClosed() {
        String valid = catalog("standard");
        for (String bad : List.of(valid.replace("\"schema\":1", "\"schema\":1.5"), valid.replace("\"size\":123", "\"size\":1.2"),
                valid.replace("\"size\":123", "\"size\":\"123\""), valid.replace(HASH, "x".repeat(64)),
                valid.replace("\"26.3\"]", "\"26.3\",\"26.3\"]"), valid.replace("MusicHud-TuneWeave\"", "wrong\""),
                valid.replace("musichud-tuneweave-fabric-", "../musichud-tuneweave-fabric-"), "null", "[]", "{}")) {
            assertThrows(RuntimeException.class, () -> ClientUpdateCatalog.select(bad, "v1.4.0-beta.1", installed("standard"), true, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> ClientUpdateCatalog.assetUri("v1.0.0+../escape", "file.jar"));
    }
    @Test void versionOrderDoesNotOfferDowngradesOrConfuseCfRevision() {
        assertTrue(ReleaseVersion.parse("1.4.0").compareTo(ReleaseVersion.parse("1.4.0-rc.9")) > 0);
        assertTrue(ReleaseVersion.parse("1.4.0-beta.10").compareTo(ReleaseVersion.parse("1.4.0-beta.2")) > 0);
        assertTrue(ReleaseVersion.parse("1.4.0-cf.2+26.3").compareTo(ReleaseVersion.parse("1.4.0-cf.1+26.3")) > 0);
        assertTrue(ReleaseVersion.parse("1.4.0-beta-10").compareTo(ReleaseVersion.parse("1.4.0-beta-3")) > 0);
        assertEquals(0, ReleaseVersion.parse("1.4.0+26.3").compareTo(ReleaseVersion.parse("1.4.0+26.2")));
        for (String bad : List.of("1.2", "01.2.3", "1.2.3-01", "1.2.3+", "1.2.3+a+b", "1.2.3+../x"))
            assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(bad));
    }
    @Test void releasePageIsAnExactGitHubTagPageAndRejectsInjectedUrls() {
        assertEquals("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/tag/v1.4.0-beta.1",
                ClientUpdateCatalog.releasePageUri("v1.4.0-beta.1").toString());
        for (String tag : List.of("../v1.4.0", "v1.4.0?next=https://evil.example", "v1.4.0#other", "https://evil.example", "v1.4.0/../x", "v1.4.0+26.3"))
            assertThrows(IllegalArgumentException.class, () -> ClientUpdateCatalog.releasePageUri(tag));
        var offer = ClientUpdateCatalog.select(catalog("cf"), "v1.4.0-beta.1", installed("cf"), true, "").orElseThrow();
        assertEquals(ClientUpdateCatalog.releasePageUri("v1.4.0-beta.1"), offer.releasePageUri());
    }
    @Test void numericHotfixOrdersBeforePrereleaseAndIndependentCfRevision() {
        List<String> ordered = List.of("1.3.1", "1.3.1.1-beta.2", "1.3.1.1-beta.10",
                "1.3.1.1", "1.3.1.1-cf.1", "1.3.1.1-cf.2", "1.3.1.2", "1.3.1.10", "1.3.2");
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ReleaseVersion.parse(ordered.get(i - 1)).compareTo(ReleaseVersion.parse(ordered.get(i))) < 0,
                    ordered.get(i - 1) + " must precede " + ordered.get(i));
        }
        assertEquals(ReleaseVersion.parse("1.3.1"), ReleaseVersion.parse("1.3.1.0"));
        assertEquals(new ReleaseVersion(1, 3, 1, List.of(), 0), ReleaseVersion.parse("1.3.1.0"));
        assertEquals(0, ReleaseVersion.parse("1.3.1.1+1.21.9-1.21.10")
                .compareTo(ReleaseVersion.parse("1.3.1.1+1.21.11")));
        assertEquals(999999999, ReleaseVersion.parse("1.3.1.999999999").hotfix());
    }

    @Test void currentHotfixIdentitiesSelectNewerUpdatesWithoutDowngradingOrCrossingEdition() {
        for (String game : List.of("1.21.9", "1.21.10", "1.21.11")) {
            String metadata = game.equals("1.21.11") ? game : "1.21.9-1.21.10";
            for (String loader : List.of("fabric", "neoforge")) for (String edition : List.of("standard", "cf")) {
                String suffix = edition.equals("cf") ? "-cf.1" : "";
                var current = new ClientUpdateCatalog.Installed("1.3.1.1" + suffix + "+" + metadata, edition, loader, game);
                var before = new ClientUpdateCatalog.Installed("1.3.1" + suffix + "+" + metadata, edition, loader, game);
                String same = hotfixCatalog("1.3.1.1", suffix, metadata, edition, loader, game);
                var offer = ClientUpdateCatalog.select(same, "v1.3.1.1", before, false, "hotfix").orElseThrow();
                assertEquals("1.3.1.1" + suffix + "+" + metadata, offer.version());
                assertEquals("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/tag/v1.3.1.1", offer.releasePageUri().toString());
                assertEquals("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/download/v1.3.1.1/musichud-tuneweave-"
                        + loader + "-1.3.1.1" + suffix + "%2B" + metadata + ".jar", offer.downloadUri().toString());
                assertTrue(ClientUpdateCatalog.select(same, "v1.3.1.1", current, false, "").isEmpty());
                assertTrue(ClientUpdateCatalog.select(hotfixCatalog("1.3.1", suffix, metadata, edition, loader, game),
                        "v1.3.1", current, true, "").isEmpty());
                assertTrue(ClientUpdateCatalog.select(hotfixCatalog("1.3.1.2", suffix, metadata, edition, loader, game),
                        "v1.3.1.2", current, false, "").isPresent());
                if (edition.equals("cf")) assertTrue(ClientUpdateCatalog.select(
                        hotfixCatalog("1.3.1.1", "-cf.2", metadata, edition, loader, game),
                        "v1.3.1.1", current, false, "").isPresent());
                assertThrows(IllegalArgumentException.class, () -> new ClientUpdateCatalog.Installed(
                        "1.3.1.1" + (edition.equals("cf") ? "" : "-cf.1") + "+" + metadata, edition, loader, game));
            }
        }
    }

    @Test void hotfixPrereleasesKeepTheExistingOptInAndMetadataSemantics() {
        var current = new ClientUpdateCatalog.Installed("1.3.1.1+1.21.11", "standard", "fabric", "1.21.11");
        String preview = hotfixCatalog("1.3.1.2-rc.1", "", "1.21.11", "standard", "fabric", "1.21.11");
        assertTrue(ClientUpdateCatalog.select(preview, "v1.3.1.2-rc.1", current, false, "").isEmpty());
        assertTrue(ClientUpdateCatalog.select(preview, "v1.3.1.2-rc.1", current, true, "").isPresent());
        assertEquals(List.of("rc", "1"), ReleaseVersion.parse("1.3.1.2-rc.1-cf.2+1.21.11").pre());
        assertEquals(2, ReleaseVersion.parse("1.3.1.2-rc.1-cf.2+1.21.11").cfRevision());
    }

    @Test void releaseTagPrefilterStripsOnlyCfRevisionAndPreservesHotfixAndPrerelease() {
        var installed = ReleaseVersion.parse("1.3.1.2-rc.1-cf.2+1.21.11");
        var base = installed.withoutCfRevision();
        assertEquals(ReleaseVersion.parse("1.3.1.2-rc.1"), base);
        assertEquals(2, installed.cfRevision());
        assertTrue(ReleaseVersion.parse("1.3.1.1").compareTo(base) < 0);
        assertEquals(0, ReleaseVersion.parse("1.3.1.2-rc.1").compareTo(base));
        assertTrue(ReleaseVersion.parse("1.3.1.2").compareTo(base) > 0);
        assertSame(base, base.withoutCfRevision());
    }

    @Test void fourthComponentAndTagBoundariesRemainStrict() {
        for (String bad : List.of("1.3.1.01", "1.3.1.-1", "1.3.1.1000000000", "1.3.1.1.0",
                "1.3.1.", "1.3.1.1-01", "1.3.1.1+", "1.3.1.1+a+b", "1.3.1.1+../x",
                "1.3.1." + "9".repeat(200), "1.3.1.1-" + "a".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(bad), bad);
        }
        for (String bad : List.of("v1.3.1.01", "v1.3.1.1000000000", "v1.3.1.1.0", "v1.3.1.1/../x",
                "v1.3.1.1?next=evil", "v1.3.1.1+1.21.11", "v1.3.1." + "9".repeat(200))) {
            assertThrows(IllegalArgumentException.class, () -> ClientUpdateCatalog.releasePageUri(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> ClientUpdateCatalog.assetUri(bad, "file.jar"), bad);
        }
    }

    private static String hotfixCatalog(String product, String suffix, String metadata, String edition, String loader, String game) {
        String version = product + suffix + "+" + metadata;
        return """
                {"schema":1,"repository":"MOPELotus/MusicHud-TuneWeave","tag":"v%s","distribution":"%s",
                "artifacts":[{"version":"%s","distribution":"%s","loader":"%s","minecraft":["%s"],
                "file":"musichud-tuneweave-%s-%s.jar","sha256":"%s","size":123}]}
                """.formatted(product, edition, version, edition, loader, game, loader, version, HASH);
    }
}
