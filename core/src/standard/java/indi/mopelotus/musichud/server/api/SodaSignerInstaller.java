package indi.mopelotus.musichud.server.api;

import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Standard-edition downloader; immutable Git blob identity prevents mixed-version updates. */
final class SodaSignerInstaller {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final String CONTENTS = "https://api.github.com/repos/MOPELotus/TuneWeave/contents/tools/auxiliary/TuneWeave-SodaSigner.exe?ref=dev";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    record Blob(String sha, long size) {}
    static Blob parse(String json) throws IOException {
        try {
            var value = JsonParser.parseString(json).getAsJsonObject();
            String sha = value.get("sha").getAsString();
            long size = value.get("size").getAsBigDecimal().longValueExact();
            if (!"file".equals(value.get("type").getAsString()) || !"TuneWeave-SodaSigner.exe".equals(value.get("name").getAsString())
                    || !sha.matches("[0-9a-f]{40}") || size < 64 || size > MAX_BYTES) throw new IllegalArgumentException();
            return new Blob(sha, size);
        } catch (RuntimeException invalid) { throw new IOException("Invalid Soda signer download metadata"); }
    }
    static HttpResponse<InputStream> request(String url, String accept) throws IOException, InterruptedException {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", MusicHud.PROJECT_NAME).header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) { response.body().close(); throw new IOException("Soda signer download returned HTTP " + response.statusCode()); }
        return response;
    }
    static SodaSignerSupport.Installation download(Path directory, ApiServerFetcher.DownloadProxy proxy,
            BiConsumer<Long, Long> progress, AtomicBoolean cancelled) throws IOException, InterruptedException {
        checkCancelled(cancelled);
        Blob blob;
        try (var input = request(proxy.resolveUrl(CONTENTS), "application/vnd.github+json").body()) {
            byte[] json = input.readNBytes(65537);
            if (json.length > 65536) throw new IOException("Soda signer metadata is too large");
            blob = parse(new String(json, StandardCharsets.UTF_8));
        }
        checkCancelled(cancelled);
        String name = ".musichud-soda-signer-" + blob.sha() + ".exe";
        Path target = directory.resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verify(target, blob); return new SodaSignerSupport.Installation(name, SodaSignerSupport.sha256(target));
        }
        Path temporary = Files.createTempFile(directory, ".musichud-signer-", ".part");
        try {
            var response = request(proxy.resolveUrl("https://api.github.com/repos/MOPELotus/TuneWeave/git/blobs/" + blob.sha()), "application/vnd.github.raw");
            try (var input = response.body(); var output = Files.newOutputStream(temporary)) {
                copy(input, output, blob.size(), cancelled, progress);
            }
            verify(temporary, blob); checkCancelled(cancelled);
            // Never replace an active signer or an unrelated file, even on a repeated/overwrite update.
            try { Files.move(temporary, target); }
            catch (FileAlreadyExistsException race) { verify(target, blob); }
            return new SodaSignerSupport.Installation(name, SodaSignerSupport.sha256(target));
        } finally { Files.deleteIfExists(temporary); } // This invocation's unique temporary file only.
    }
    static void checkCancelled(AtomicBoolean cancelled) {
        if ((cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted()) throw new CancellationException("Download cancelled");
    }
    static void copy(InputStream input, OutputStream output, long expected, AtomicBoolean cancelled,
                     BiConsumer<Long, Long> progress) throws IOException {
        if (expected < 64 || expected > MAX_BYTES) throw new IOException("Invalid Soda signer size");
        byte[] buffer = new byte[32768]; long count = 0; int n;
        while ((n = input.read(buffer)) >= 0) {
            checkCancelled(cancelled); count += n;
            if (count > expected) throw new IOException("Soda signer exceeds declared size");
            output.write(buffer, 0, n); if (progress != null) progress.accept(count, expected);
        }
        checkCancelled(cancelled);
        if (count != expected) throw new IOException("Incomplete Soda signer download");
    }
    static void verify(Path file, Blob blob) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != blob.size()) throw new IOException("Invalid Soda signer file");
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + blob.size() + "\0").getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[32768]; int n; boolean first = true;
            while ((n = input.read(buffer)) >= 0) {
                if (first && (n < 2 || buffer[0] != 'M' || buffer[1] != 'Z')) throw new IOException("Soda signer is not a Windows executable");
                first = false; digest.update(buffer, 0, n);
            }
            if (!blob.sha().equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Soda signer Git blob verification failed");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
