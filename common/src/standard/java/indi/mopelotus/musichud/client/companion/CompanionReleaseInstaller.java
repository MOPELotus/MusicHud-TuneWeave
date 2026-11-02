package indi.mopelotus.musichud.client.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.ZipInputStream;

/** Installs publisher-verified app images into fresh, owned directories only. */
public final class CompanionReleaseInstaller {
    public static final URI MANIFEST = URI.create("https://github.com/MOPELotus/MusicHud-TuneWeave/releases/latest/download/companion-latest.json");
    static final URI RELEASES = URI.create("https://api.github.com/repos/MOPELotus/MusicHud-TuneWeave/releases?per_page=20");
    public static final String DOWNLOAD_PAGE = "https://github.com/MOPELotus/MusicHud-TuneWeave/releases";
    public static final int PROTOCOL_VERSION = 1;
    private static final long MAX_ARCHIVE = 512L * 1024 * 1024;
    private static final long MAX_EXPANDED = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    public record Descriptor(String version, URI url, String sha256, long size, String entryPoint) {}
    public record Result(Path executable, String version) {}
    @FunctionalInterface public interface Transport {
        InputStream open(URI uri, AtomicBoolean cancelled) throws IOException, InterruptedException;
    }
    private final Transport transport;

    public CompanionReleaseInstaller() { this(CompanionReleaseInstaller::openOfficial); }
    public CompanionReleaseInstaller(Transport transport) { this.transport = Objects.requireNonNull(transport); }

    public Descriptor fetchLatest(AtomicBoolean cancelled) throws IOException, InterruptedException {
        try { return fetchManifest(MANIFEST, cancelled); }
        catch (HttpFailure unavailable) {
            if (unavailable.status != 404) throw unavailable;
            // Scoped hotfix releases need not repeat the desktop package. Search a
            // bounded official catalog only when latest genuinely lacks its asset.
            try (InputStream input = cancellable(transport.open(RELEASES, cancelled), cancelled)) {
                byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                checkCancelled(cancelled);
                if (bytes.length > 2 * 1024 * 1024) throw new IOException("Companion release catalog is too large");
                return fetchManifest(findCatalog(new String(bytes, StandardCharsets.UTF_8)), cancelled);
            }
        }
    }

    private Descriptor fetchManifest(URI uri, AtomicBoolean cancelled) throws IOException, InterruptedException {
        checkCancelled(cancelled);
        try (InputStream input = cancellable(transport.open(uri, cancelled), cancelled)) {
            byte[] bytes = input.readNBytes(65_537);
            checkCancelled(cancelled);
            if (bytes.length > 65_536) throw new IOException("Companion manifest is too large");
            return parseManifest(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    static URI findCatalog(String json) throws IOException {
        try {
            var root = JsonParser.parseString(json);
            if (!root.isJsonArray() || root.getAsJsonArray().size() > 20) throw new IOException("Invalid Companion release catalog count");
            URI selected = null;
            java.time.Instant newest = null;
            for (var element : root.getAsJsonArray()) {
                JsonObject release = element.getAsJsonObject();
                boolean draft = bool(release, "draft"), prerelease = bool(release, "prerelease");
                var published = release.get("published_at");
                if (published == null) throw new IOException("Missing release publication timestamp");
                java.time.Instant time = published.isJsonNull() ? null : java.time.Instant.parse(string(release, "published_at", 64));
                var assets = release.get("assets");
                if (assets == null || !assets.isJsonArray() || assets.getAsJsonArray().size() > 100) throw new IOException("Invalid Companion release asset count");
                URI candidate = null;
                for (var assetElement : assets.getAsJsonArray()) {
                    JsonObject asset = assetElement.getAsJsonObject();
                    String name = string(asset, "name", 255);
                    String url = string(asset, "browser_download_url", 2048);
                    if (!name.equals("companion-latest.json")) continue;
                    URI uri = URI.create(url);
                    if (!secure(uri) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getQuery() != null
                            || !uri.getRawPath().matches("/MOPELotus/MusicHud-TuneWeave/releases/download/[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}/companion-latest\\.json"))
                        throw new IOException("Untrusted Companion release catalog URL");
                    if (candidate != null) throw new IOException("Duplicate Companion release catalog assets");
                    candidate = uri;
                }
                if (!draft && !prerelease && time != null && candidate != null && (newest == null || time.isAfter(newest))) {
                    selected = candidate;
                    newest = time;
                }
            }
            if (selected == null) throw new IOException("No published Companion catalog found in the latest 20 releases");
            return selected;
        } catch (RuntimeException malformed) { throw new IOException("Invalid Companion release catalog", malformed); }
    }

    private static String string(JsonObject object, String field, int max) throws IOException {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > max || value.getAsString().isEmpty()) throw new IOException("Invalid release field: " + field);
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String field) throws IOException {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid release flag: " + field);
        return value.getAsBoolean();
    }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Official Companion release unavailable (HTTP " + status + "); use manual download if not published yet"); this.status = status; }
    }

    public Result install(Path root, AtomicBoolean cancelled, BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows") || !(arch.equals("amd64") || arch.equals("x86_64")))
            throw new IOException("Managed Companion download currently supports Windows x64 only");
        return install(root, fetchLatest(cancelled), cancelled, progress);
    }

    /** Descriptor overload also supports offline, deterministic verification. */
    public Result install(Path root, Descriptor descriptor, AtomicBoolean cancelled,
                          BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        validate(descriptor);
        checkCancelled(cancelled);
        Path versions = root.toAbsolutePath().normalize().resolve("versions");
        createSafeDirectories(versions);
        Path staging = Files.createTempDirectory(versions, ".install-");
        boolean installed = false;
        try {
            Path archive = staging.resolve("download.zip");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            long received = 0;
            try (InputStream input = cancellable(transport.open(descriptor.url(), cancelled), cancelled);
                 OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65_536];
                for (int count; (count = input.read(buffer)) != -1;) {
                    checkCancelled(cancelled);
                    received += count;
                    if (received > descriptor.size()) throw new IOException("Companion archive exceeds published size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (progress != null) progress.accept(received, descriptor.size());
                }
            }
            checkCancelled(cancelled);
            if (received != descriptor.size() || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(descriptor.sha256()))
                throw new IOException("Companion archive size or SHA-256 does not match publisher metadata");
            Path image = Files.createDirectory(staging.resolve("image"));
            extract(archive, image, cancelled);
            Path executable = image.resolve(descriptor.entryPoint());
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(image.resolve("Companion/app"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(image.resolve("Companion/runtime"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Companion archive is not a complete app image");
            checkCancelled(cancelled);
            Path destination = versions.resolve(descriptor.version() + "-" + UUID.randomUUID());
            try { Files.move(image, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(image, destination); }
            installed = true;
            return new Result(destination.resolve(descriptor.entryPoint()), descriptor.version());
        } finally {
            // Only our randomly created staging directory is ever removed. Existing versions are untouched.
            try { removeOwnedTree(staging); }
            catch (IOException cleanup) { if (!installed) throw cleanup; }
        }
    }

    public static Descriptor parseManifest(String json) throws IOException {
        try {
            JsonObject manifest = JsonParser.parseString(json).getAsJsonObject();
            if (integer(manifest, "schemaVersion") != 1 || integer(manifest, "protocolVersion") != PROTOCOL_VERSION)
                throw new IOException("Unsupported Companion manifest or display protocol");
            JsonObject platform = manifest.getAsJsonObject("platforms").getAsJsonObject("windows-x64");
            Descriptor descriptor = new Descriptor(manifest.get("version").getAsString(),
                    URI.create(platform.get("url").getAsString()), platform.get("sha256").getAsString(),
                    integer(platform, "size"), platform.get("entryPoint").getAsString());
            validate(descriptor);
            return descriptor;
        } catch (RuntimeException malformed) { throw new IOException("Invalid Companion release manifest", malformed); }
    }

    private static long integer(JsonObject object, String field) throws IOException {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]{1,18}")) throw new IOException("Invalid Companion manifest integer: " + field);
        return Long.parseLong(value.getAsString());
    }

    private static void validate(Descriptor descriptor) throws IOException {
        if (descriptor == null || descriptor.version() == null || !descriptor.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")
                || descriptor.version().length() > 100 || descriptor.sha256() == null || !descriptor.sha256().matches("[A-Fa-f0-9]{64}")
                || descriptor.size() < 1 || descriptor.size() > MAX_ARCHIVE || !"Companion/Companion.exe".equals(descriptor.entryPoint()))
            throw new IOException("Invalid Companion release identity, checksum, size or entry point");
        URI uri = descriptor.url();
        if (!secure(uri) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getQuery() != null
                || !uri.getPath().startsWith("/MOPELotus/MusicHud-TuneWeave/releases/download/")
                || !uri.getPath().endsWith(".zip") || uri.getRawPath().contains("%") || uri.getPath().contains("/../"))
            throw new IOException("Companion archive must come from the official GitHub releases");
    }

    private static void extract(Path archive, Path image, AtomicBoolean cancelled) throws IOException {
        long expanded = 0;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                checkCancelled(cancelled);
                String name = entry.getName();
                if (++entries > MAX_ENTRIES || name.isEmpty() || name.length() > 1024 || name.startsWith("/")
                        || name.contains("\\") || name.matches(".*[<>:\"|?*\\x00-\\x1F].*"))
                    throw new IOException("Unsafe Companion ZIP entry");
                String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                for (String component : trimmed.split("/", -1)) {
                    if (component.isEmpty() || component.equals(".") || component.equals("..") || component.endsWith(".") || component.endsWith(" ")
                            || component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                        throw new IOException("Unsafe Companion ZIP path component");
                }
                if (!trimmed.startsWith("Companion/") && !trimmed.equals("Companion"))
                    throw new IOException("Companion ZIP entry is outside app image");
                if (!names.add(trimmed.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate Companion ZIP entry");
                Path target = image.resolve(trimmed).normalize();
                if (!target.startsWith(image)) throw new IOException("Companion ZIP traversal");
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65_536];
                        for (int count; (count = zip.read(buffer)) != -1;) {
                            checkCancelled(cancelled);
                            expanded += count;
                            if (expanded > MAX_EXPANDED) throw new IOException("Companion ZIP expands beyond limit");
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
        }
    }

    private static void createSafeDirectories(Path directory) throws IOException {
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Companion path contains a symlink or non-directory");
            } else Files.createDirectory(current);
        }
    }

    private static void removeOwnedTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Companion download cancelled");
    }

    private static InputStream cancellable(InputStream input, AtomicBoolean cancelled) {
        AtomicBoolean closed = new AtomicBoolean();
        // HttpClient's request timeout ends when streaming headers arrive. Also bound and cancel body reads.
        Thread watcher = Thread.ofVirtual().name("companion-download-cancellation").start(() -> {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            try {
                while (!closed.get()) {
                    if (cancelled.get() || System.nanoTime() >= deadline) {
                        try { input.close(); } catch (IOException ignored) { }
                        return;
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException finished) { Thread.currentThread().interrupt(); }
        });
        return new FilterInputStream(input) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                checkCancelled(cancelled);
                try { return super.read(buffer, offset, length); }
                catch (IOException failure) { checkCancelled(cancelled); throw failure; }
            }
            @Override public void close() throws IOException {
                closed.set(true);
                watcher.interrupt();
                super.close();
            }
        };
    }

    private static boolean secure(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static InputStream openOfficial(URI initial, AtomicBoolean cancelled) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        URI uri = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCancelled(cancelled);
            if (!secure(uri) || !(Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.equals(RELEASES)))
                throw new IOException("Untrusted Companion download redirect");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "MusicHud-TuneWeave-Companion").GET().build();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                while (true) {
                    checkCancelled(cancelled);
                    try { response = pending.get(100, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException waiting) { /* Observe cancellation while connecting. */ }
                }
            } catch (InterruptedException | CancellationException interrupted) {
                if (!pending.cancel(true) && !pending.isCompletedExceptionally()) {
                    var completed = pending.getNow(null);
                    if (completed != null) completed.body().close();
                }
                throw interrupted;
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof IOException io) throw io;
                throw new IOException("Companion download failed", failed.getCause());
            }
            if (response.statusCode() == 200) return response.body();
            response.body().close();
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                if (uri.equals(RELEASES)) throw new IOException("Unexpected GitHub API redirect");
                String location = response.headers().firstValue("location").orElseThrow(() -> new IOException("Companion redirect has no location"));
                uri = uri.resolve(location);
            } else throw new HttpFailure(response.statusCode());
        }
        throw new IOException("Too many Companion download redirects");
    }
}
