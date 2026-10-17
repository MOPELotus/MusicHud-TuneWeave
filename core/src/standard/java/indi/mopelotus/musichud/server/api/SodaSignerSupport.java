package indi.mopelotus.musichud.server.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Client-local companion metadata. No token is persisted or placed in process arguments. */
final class SodaSignerSupport {
    static final String SUFFIX = ".musichud-soda-signer.json";
    static final String OWNER = "musichud-tuneweave-soda-signer-v1";
    record Installation(String file, String sha256) {}
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }
    static Path descriptor(Path binary) { return binary.resolveSibling(binary.getFileName() + SUFFIX); }
    static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Path validate(Path directory, Installation installation) throws IOException {
        if (installation == null || installation.file() == null || !installation.file().matches("\\.musichud-soda-signer-[0-9a-f]{40}\\.exe")
                || installation.sha256() == null || !installation.sha256().matches("[0-9a-f]{64}"))
            throw new IOException("Invalid managed Soda signer metadata");
        Path file = directory.resolve(installation.file());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !sha256(file).equals(installation.sha256()))
            throw new IOException("Managed Soda signer is missing or has changed; download TuneWeave again");
        return file;
    }
    static Installation read(Path binary) throws IOException {
        Path file = descriptor(binary);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096)
            throw new IOException("Invalid managed Soda signer descriptor");
        try {
            var value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!OWNER.equals(value.get("owner").getAsString())) throw new IllegalArgumentException();
            var installation = new Installation(value.get("file").getAsString(), value.get("sha256").getAsString());
            validate(binary.toAbsolutePath().getParent(), installation); return installation;
        } catch (RuntimeException error) { throw new IOException("Invalid managed Soda signer descriptor"); }
    }
    static void writeNew(Path binary, Installation installation) throws IOException {
        validate(binary.toAbsolutePath().getParent(), installation);
        JsonObject value = new JsonObject(); value.addProperty("owner", OWNER);
        value.addProperty("file", installation.file()); value.addProperty("sha256", installation.sha256());
        Files.writeString(descriptor(binary), value.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    static Process launch(Path binary, ProcessBuilder main) throws IOException {
        if (!windows() || main.environment().containsKey("TUNEWEAVE_SODA_BDMS_SERVICE_URL")) return main.start();
        Installation installation = read(binary);
        if (installation == null) return main.start(); // External/manual installations remain caller-configured.
        Path signer = validate(binary.toAbsolutePath().getParent(), installation);
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String origin = "http://127.0.0.1:" + port;
        ProcessBuilder child = new ProcessBuilder(signer.toString());
        child.directory(binary.toAbsolutePath().getParent().toFile());
        configurePair(main, child, port, token);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return new SodaSignerProcess(child.start(), () -> main.start(), () -> {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/healthz"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    byte[] bytes = body.readNBytes(4097);
                    return response.statusCode() == 200 && bytes.length <= 4096
                            && JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject().get("ok").getAsBoolean();
                }
            } catch (Exception unavailable) { return false; }
        });
    }
    static void configurePair(ProcessBuilder main, ProcessBuilder child, int port, String token) {
        child.environment().put("TUNEWEAVE_SODA_BDMS_BIND", "127.0.0.1:" + port);
        child.environment().put("TUNEWEAVE_SODA_BDMS_TOKEN", token);
        child.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        child.redirectError(ProcessBuilder.Redirect.DISCARD);
        main.environment().put("TUNEWEAVE_SODA_BDMS_SERVICE_URL", "http://127.0.0.1:" + port);
        main.environment().put("TUNEWEAVE_SODA_BDMS_SERVICE_TOKEN", token);
    }

}
