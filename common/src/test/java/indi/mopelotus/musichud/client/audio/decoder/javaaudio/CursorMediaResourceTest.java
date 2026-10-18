package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CursorMediaResourceTest {
  @TempDir Path directory;

  @Test
  @Timeout(10)
  void cancellingStalledCursorDoesNotBlockAnotherSeekOrDeleteUserFiles() throws Exception {
    byte[] bytes = new byte[1024 * 1024];
    new Random(713).nextBytes(bytes);
    CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1);
    var executor = Executors.newCachedThreadPool();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          String range = exchange.getRequestHeaders().getFirst("Range");
          int first = range == null ? 0 : Integer.parseInt(range.substring(6).split("-")[0]);
          exchange.getResponseHeaders().set("ETag", "\"one-song\"");
          if (range != null)
            exchange
                .getResponseHeaders()
                .set(
                    "Content-Range",
                    "bytes " + first + "-" + (bytes.length - 1) + "/" + bytes.length);
          exchange.sendResponseHeaders(range == null ? 200 : 206, bytes.length - first);
          if (first == 512 * 1024) {
            stalled.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          try (var output = exchange.getResponseBody()) {
            output.write(bytes, first, bytes.length - first);
          } catch (IOException cancelled) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    Path userFile = directory.resolve("cursor-owned-user-file.cache");
    Files.writeString(userFile, "keep");
    try (var transport = new MediaTransport();
        var resource =
            new CursorMediaResource(
                CursorMediaResource.transport(
                    transport,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Map.of(),
                    false),
                directory,
                2 * 1024 * 1024)) {
      var old = resource.openCursor();
      assertArrayEquals(Arrays.copyOf(bytes, 8), old.readNBytes(8));
      old.position(512 * 1024);
      Future<?> read =
          executor.submit(() -> assertThrows(IOException.class, () -> old.readNBytes(1)));
      assertTrue(stalled.await(2, TimeUnit.SECONDS));
      long start = System.nanoTime();
      old.close();
      try (var next = resource.openCursor()) {
        next.position(256 * 1024);
        assertArrayEquals(
            Arrays.copyOfRange(bytes, 256 * 1024, 256 * 1024 + 8), next.readNBytes(8));
      }
      read.get(2, TimeUnit.SECONDS);
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
    } finally {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
    assertEquals("keep", Files.readString(userFile));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(userFile), files.toList());
    }
  }

  @Test
  @Timeout(10)
  void unvalidatedResponsesCannotShareParserMetadataOrCacheAcrossCursors() throws Exception {
    byte[] body = {1, 2, 3, 4};
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        x -> {
          x.sendResponseHeaders(200, body.length);
          try (var out = x.getResponseBody()) {
            out.write(body);
          } finally {
            x.close();
          }
        });
    server.start();
    try (var transport = new MediaTransport();
        var resource =
            new CursorMediaResource(
                CursorMediaResource.transport(
                    transport,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Map.of(),
                    false),
                directory,
                1024)) {
      CursorMediaResource.Identity identity;
      try (var first = resource.openCursor()) {
        identity = first.identity();
        assertArrayEquals(body, first.readNBytes(4));
      }
      try (var next = resource.openCursor()) {
        assertNotEquals(identity, next.identity());
        assertThrows(IOException.class, () -> next.requireIdentity(identity));
        assertArrayEquals(body, next.readNBytes(4));
      }
    } finally {
      server.stop(0);
    }
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }
}
