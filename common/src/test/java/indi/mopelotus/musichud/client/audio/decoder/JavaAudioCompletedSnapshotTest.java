package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class JavaAudioCompletedSnapshotTest {
  @TempDir Path directory;

  @Test
  void localDetectionAndSnapshotInspectionDoNotOpenEvenANonexistentFile() throws Exception {
    try (var resource =
        new JavaAudioResource(directory.resolve("absent.mp3").toString(), Map.of(), false, false)) {
      assertTrue(resource.isLocal());
      assertFalse(resource.hasCompletedSnapshot());
      assertTrue(resource.completedSnapshot().isEmpty());
    }
  }

  @Test
  void malformedSignedHttpUrlDoesNotRetainRawInputInAnExceptionCause() {
    String raw = "https://example.test/invalid path?signature=private-value";
    var error =
        assertThrows(IOException.class, () -> new JavaAudioResource(raw, Map.of(), false, false));
    assertEquals("Invalid media URL", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  @Timeout(10)
  void capturedWeakResponseCanBeIndexedAfterItsPlaybackLeaseClosesWithoutAnotherGet()
      throws Exception {
    byte[] bytes = {11, 22, 33, 44, 55, 66};
    AtomicInteger requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var resource =
        new JavaAudioResource(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, false)) {
      assertFalse(resource.isLocal());
      assertFalse(resource.hasCompletedSnapshot());
      assertTrue(resource.completedSnapshot().isEmpty());
      assertEquals(0, requests.get());
      try (var lease = resource.lease()) {
        var input = lease.stream();
        assertEquals(bytes[0], input.read());
        assertFalse(resource.hasCompletedSnapshot());
        assertTrue(resource.completedSnapshot().isEmpty());
        assertArrayEquals(new byte[] {22, 33, 44, 55, 66}, input.readAllBytes());
      }
      try (var snapshot = resource.completedSnapshot().orElseThrow()) {
        assertTrue(resource.hasCompletedSnapshot());
        assertEquals(bytes.length, snapshot.length());
        var view = snapshot.openChannel();
        var target = ByteBuffer.allocate(bytes.length);
        assertEquals(bytes.length, view.read(target));
        assertArrayEquals(bytes, target.array());
        assertEquals(1, requests.get());
        resource.close();
        assertThrows(IOException.class, () -> view.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, resource::completedSnapshot);
        assertFalse(resource.hasCompletedSnapshot());
        view.close();
      }
    } finally {
      server.stop(0);
    }
  }
}
