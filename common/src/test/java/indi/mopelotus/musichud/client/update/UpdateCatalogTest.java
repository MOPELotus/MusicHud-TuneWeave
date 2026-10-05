package indi.mopelotus.musichud.client.update;

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
}
