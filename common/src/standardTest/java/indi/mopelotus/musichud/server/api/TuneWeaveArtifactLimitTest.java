package indi.mopelotus.musichud.server.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static indi.mopelotus.musichud.server.api.ApiServerFetcher.DownloadProxy.*;

class TuneWeaveArtifactLimitTest {
    @TempDir Path directory;
    private static final String RELEASE = "https://github.com/MOPELotus/TuneWeave/releases/download/v1/limit-test";
    private static final byte[] VALID = "verified binary".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test void advertisedOversizeRejectsBeforeReadingAndClosesInputWithoutFiles() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new InputStream() {
            public int read() { fail("Oversize header must reject before reading"); return -1; }
            public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> stage(input, 17, 16));
        assertTrue(closed.get()); assertFiles();
    }
    @Test void missingLengthCannotStreamIndefinitelyOrExceedTheActualByteLimit() throws Exception {
        Path target = directory.resolve("active-binary"); Files.writeString(target, "sentinel");
        Path unrelated = directory.resolve(".musichud-tuneweave-user.part"); Files.writeString(unrelated, "unrelated");
        AtomicInteger read = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
        InputStream endless = new InputStream() {
            public int read() { read.incrementAndGet(); return 7; }
            public int read(byte[] bytes, int offset, int length) { Arrays.fill(bytes, offset, offset + length, (byte) 7); read.addAndGet(length); return length; }
            public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> stage(endless, -1, 16));
        assertEquals(17, read.get()); assertTrue(closed.get());
        assertEquals("sentinel", Files.readString(target)); assertEquals("unrelated", Files.readString(unrelated));
        assertFiles("active-binary", ".musichud-tuneweave-user.part");
    }
    @Test void falseLengthTruncationAndHashFailureCleanOnlyTheirOwnPartialFile() throws Exception {
        Files.writeString(directory.resolve("keep.part"), "user data");
        assertThrows(IOException.class, () -> stage(new ByteArrayInputStream(VALID), 1, 64));
        assertThrows(IOException.class, () -> stage(new ByteArrayInputStream(VALID), VALID.length + 1, 64));
        assertThrows(IOException.class, () -> ApiServerFetcher.stageVerifiedArtifact(new ByteArrayInputStream(VALID), directory,
                VALID.length, 64, "00".repeat(32), null, null));
        assertFiles("keep.part"); assertEquals("user data", Files.readString(directory.resolve("keep.part")));
    }
    @Test void autoOversizeFallbackUsesFreshFileAndPreservesTargetUntilVerifiedSuccess() throws Exception {
        Path target = directory.resolve("target"); Files.writeString(target, "sentinel");
        DownloadSources.cacheRanking(RELEASE + "\napplication/octet-stream", List.of(FASTLY_IPV4, DIRECT), System.nanoTime());
        List<Object> attempts = new ArrayList<>();
        Path verified = DownloadSources.withSource(RELEASE, AUTO, null, source -> {
            attempts.add(source);
            assertFiles("target"); assertEquals("sentinel", Files.readString(target));
            return stage(new ByteArrayInputStream(source == FASTLY_IPV4 ? new byte[100] : VALID), -1, VALID.length);
        });
        assertEquals(List.of(FASTLY_IPV4, DIRECT), attempts);
        assertEquals("sentinel", Files.readString(target)); assertArrayEquals(VALID, Files.readAllBytes(verified));
        Files.move(verified, target, StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(VALID, Files.readAllBytes(target)); assertFiles("target");
    }
    @Test void manualOversizeDoesNotSwitchSourceOrReplaceTarget() throws Exception {
        Path target = directory.resolve("target"); Files.writeString(target, "sentinel"); AtomicInteger attempts = new AtomicInteger();
        assertThrows(IOException.class, () -> DownloadSources.withSource(RELEASE, FASTLY_IPV4, null, source -> {
            attempts.incrementAndGet(); return stage(new ByteArrayInputStream(new byte[100]), -1, 16);
        }));
        assertEquals(1, attempts.get()); assertEquals("sentinel", Files.readString(target)); assertFiles("target");
    }
    @Test void cancellationAfterBytesWereWrittenCleansPartialAndLeavesOtherFiles() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(); Files.writeString(directory.resolve("active"), "sentinel");
        assertThrows(CancellationException.class, () -> ApiServerFetcher.stageVerifiedArtifact(new ByteArrayInputStream(VALID), directory,
                -1, 64, sha(), (done, total) -> cancelled.set(true), cancelled));
        assertFiles("active"); assertEquals("sentinel", Files.readString(directory.resolve("active")));
    }
    @Test void exactLimitIsAcceptedWithAnUnknownLengthAfterFullHashVerification() throws Exception {
        Path verified = stage(new ByteArrayInputStream(VALID), -1, VALID.length);
        assertArrayEquals(VALID, Files.readAllBytes(verified)); assertFiles(verified.getFileName().toString());
    }
    private Path stage(InputStream input, long declared, long limit) throws Exception {
        return ApiServerFetcher.stageVerifiedArtifact(input, directory, declared, limit, sha(), null, null);
    }
    private static String sha() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(VALID)); }
    private void assertFiles(String... expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(new TreeSet<>(List.of(expected)), new TreeSet<>(files.map(path -> path.getFileName().toString()).toList()));
        }
    }
}
