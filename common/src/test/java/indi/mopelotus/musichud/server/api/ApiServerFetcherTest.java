package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.MusicHud;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
public class ApiServerFetcherTest {
    private static final Logger LOG = MusicHud.getLogger(ApiServerFetcherTest.class);

    @SneakyThrows
    @Test
    void testCurrentTuneWeaveManifestArtifact() {
        ApiServerFetcher.TuneWeaveManifest manifest = ApiServerFetcher.fetchTuneWeaveManifest().get();
        assertNotNull(manifest);
        assertFalse(manifest.getVersion().isBlank());
        assertFalse(manifest.getTag().isBlank());
        assertTrue(manifest.getReleasePage().startsWith("https://github.com/MOPELotus/TuneWeave/releases/"));

        ApiServerFetcher.TuneWeaveArtifact artifact = ApiServerFetcher.currentTuneWeaveArtifact(manifest);
        assertNotNull(artifact);
        assertTrue(artifact.getFile().startsWith("tuneweave-"));
        assertTrue(artifact.getDownloadUrl().contains("/MOPELotus/TuneWeave/releases/download/"));
        assertEquals("sha256", artifact.getVerification().getAlgorithm());
        assertTrue(artifact.getVerification().getChecksumUrl().endsWith(".sha256"));
        LOG.info("Selected TuneWeave {} artifact {}", manifest.getTag(), artifact.getFile());
    }

    @SneakyThrows
    @Test
    void testDownloadCurrentTuneWeaveArtifactWithVerification() {
        Path tmpDir = Files.createTempDirectory("musichud-tuneweave-test-");
        try {
            ApiServerFetcher.TuneWeaveManifest manifest = ApiServerFetcher.fetchTuneWeaveManifest().get();
            ApiServerFetcher.TuneWeaveArtifact artifact = ApiServerFetcher.currentTuneWeaveArtifact(manifest);
            Path downloaded = tmpDir.resolve(artifact.getFile());

            ApiServerFetcher.downloadTuneWeaveArtifact(
                    artifact,
                    downloaded,
                    ApiServerFetcher.DownloadProxy.DIRECT,
                    null,
                    new AtomicBoolean(false)
            ).get();

            assertTrue(Files.exists(downloaded));
            assertTrue(Files.size(downloaded) > 0);
            if (!"windows".equalsIgnoreCase(artifact.getPlatform())) {
                assertTrue(downloaded.toFile().canExecute());
            }
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
            dir.toFile().deleteOnExit();
        }
    }
}
