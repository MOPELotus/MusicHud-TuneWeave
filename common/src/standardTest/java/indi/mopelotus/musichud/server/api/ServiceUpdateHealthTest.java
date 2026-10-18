package indi.mopelotus.musichud.server.api;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ServiceUpdateHealthTest {
    @Test
    void acceptedTcpWithoutHeadersHasBoundedTimeoutAndIsCancelled() throws Exception {
        try (var server = new LocalServer(false, null)) {
            long start = System.nanoTime();
            assertEquals(Optional.empty(), ServiceUpdateHealth.serverVersionAt(server.url(), Duration.ofMillis(250)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 2500, "A stalled socket must not enter the generic API retry path: " + elapsed);
            assertTrue(server.disconnected.get(3, TimeUnit.SECONDS), "Timeout must cancel the underlying exchange");
        }
    }

    @Test
    void headersWithNeverEndingBodyShareTheSameDeadlineAndCancelSocket() throws Exception {
        try (var server = new LocalServer(true, null)) {
            long start = System.nanoTime();
            assertEquals(Optional.empty(), ServiceUpdateHealth.serverVersionAt(server.url(), Duration.ofMillis(250)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 2500, "Receiving headers must not reset the total budget: " + elapsed);
            assertTrue(server.disconnected.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void productionBudgetCapsOversizedCallerBudgetAtTwoSeconds() throws Exception {
        try (var server = new LocalServer(true, null)) {
            long start = System.nanoTime();
            assertTrue(ServiceUpdateHealth.serverVersionAt(server.url(), Duration.ofMinutes(5)).isEmpty());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 4000, "Production probe budget must remain two seconds: " + elapsed);
            assertTrue(server.disconnected.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void exactHealthVersionAndMalformedOrOversizedBodies() throws Exception {
        try (var server = new LocalServer(true, "{\"data\":{\"version\":\"1.0.0-beta.1\"}}")) {
            assertEquals(Optional.of("1.0.0-beta.1"), ServiceUpdateHealth.serverVersionAt(server.url(), Duration.ofSeconds(2)));
        }
        for (String body : new String[]{"not-json", "{\"data\":{\"version\":123}}", "{\"error\":{},\"data\":{\"version\":\"1\"}}", "x".repeat(65537)}) {
            try (var server = new LocalServer(true, body)) {
                assertTrue(ServiceUpdateHealth.serverVersionAt(server.url(), Duration.ofSeconds(2)).isEmpty());
            }
        }
        assertTrue(ServiceUpdateHealth.serverVersionAt("https://external.example", Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    void configuredExternalBoundaryPreservesBasePathWithoutForwardingCredentials() throws Exception {
        try (var server = new LocalServer(true, "{\"data\":{\"version\":\"2.0.0\"}}")) {
            assertEquals(Optional.of("2.0.0"), ServiceUpdateHealth.configuredServerVersionAt(server.url() + "/prefix/"));
            assertTrue(server.requestLine.get(1, TimeUnit.SECONDS).startsWith("GET /prefix/healthz HTTP/"));
        }
        assertTrue(ServiceUpdateHealth.configuredServerVersionAt("http://user:secret@127.0.0.1:9999").isEmpty());
        assertTrue(ServiceUpdateHealth.configuredServerVersionAt("file:///tmp/service").isEmpty());
    }

    @Test
    void localPortProbeRejectsOccupiedLiteralAndLocalhostWithoutDisturbingListener() throws Exception {
        int port;
        try (var occupied = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            assertFalse(ServiceUpdateHealth.localPortAvailableForStart("http://127.0.0.1:" + port));
            assertFalse(ServiceUpdateHealth.localPortAvailableForStart("http://localhost:" + port));
            try (var connection = new Socket()) {
                connection.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
                occupied.setSoTimeout(1000);
                try (var accepted = occupied.accept()) { assertTrue(accepted.isConnected()); }
            }
        }
        // The just-closed listener may leave TIME_WAIT connections: that must not prevent stopped-service repair.
        assertTrue(ServiceUpdateHealth.localPortAvailableForStart("http://127.0.0.1:" + port));
        assertFalse(ServiceUpdateHealth.localPortAvailableForStart("http://external.example:7832"));
        assertFalse(ServiceUpdateHealth.localPortAvailableForStart("http://127.0.0.1:0"));
        assertFalse(ServiceUpdateHealth.localPortAvailableForStart("http://localhost:7832/prefix"));
    }

    /** Real TCP peers: the stalled cases never finish the HTTP body; client cancellation must close the socket. */
    private static final class LocalServer implements AutoCloseable {
        final ServerSocket listener;
        final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
        final CompletableFuture<String> requestLine = new CompletableFuture<>();
        final Thread worker;
        volatile Socket peer;
        LocalServer(boolean sendHeaders, String body) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = Thread.ofVirtual().start(() -> {
                try (Socket socket = listener.accept()) {
                    peer = socket;
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var header = new java.io.ByteArrayOutputStream();
                    int matched = 0;
                    byte[] end = {'\r', '\n', '\r', '\n'};
                    for (int value; matched < end.length && (value = input.read()) != -1;) {
                        header.write(value);
                        matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
                    }
                    requestLine.complete(header.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0]);
                    if (sendHeaders) {
                        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                        int length = body == null ? 1024 : bytes.length;
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        if (body != null) socket.getOutputStream().write(bytes);
                        socket.getOutputStream().flush();
                        if (body != null) { disconnected.complete(false); return; }
                    }
                    disconnected.complete(input.read() == -1);
                } catch (Exception failure) { disconnected.completeExceptionally(failure); }
            });
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort(); }
        @Override public void close() throws Exception {
            listener.close();
            if (peer != null) peer.close();
            worker.join(1000);
        }
    }
}
