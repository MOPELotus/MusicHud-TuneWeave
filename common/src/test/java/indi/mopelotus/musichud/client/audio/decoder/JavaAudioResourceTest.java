package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JavaAudioResourceTest {
  @Test
  void weakKnownLengthSnapshotCompletesOneBodyAndOwnsIndependentViews() throws Exception {
    byte[] bytes = new byte[256 * 1024];
    new Random(19).nextBytes(bytes);
    AtomicInteger requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try (var resource =
        new JavaAudioResource(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, false)) {
      var snapshot = resource.newStableSession();
      snapshot.initialize();
      assertEquals(bytes.length, snapshot.length());
      var first = snapshot.openChannel();
      var second = snapshot.openChannel();
      first.position(bytes.length - 31);
      second.position(5);
      var tail = ByteBuffer.allocate(31);
      assertEquals(31, first.read(tail));
      assertArrayEquals(Arrays.copyOfRange(bytes, bytes.length - 31, bytes.length), tail.array());
      first.close();
      var prefix = ByteBuffer.allocate(7);
      assertEquals(7, second.read(prefix));
      assertArrayEquals(Arrays.copyOfRange(bytes, 5, 12), prefix.array());
      assertEquals(1, requests.get());
      snapshot.close();
      assertThrows(IOException.class, () -> second.read(ByteBuffer.allocate(1)));
      second.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  @Timeout(10)
  void lazyChannelIsPublishedBeforeBlockingHeadersAndCloseCancelsIt() throws Exception {
    CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
    var workers = Executors.newCachedThreadPool();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext(
        "/",
        exchange -> {
          requested.countDown();
          try {
            release.await();
            exchange.sendResponseHeaders(200, 4);
            try (var out = exchange.getResponseBody()) {
              out.write(new byte[4]);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException cancelled) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var resource =
        new JavaAudioResource(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, false)) {
      var lease = resource.lease();
      var channel = lease.channel();
      assertEquals(1, requested.getCount());
      Future<?> opening = workers.submit(() -> assertThrows(IOException.class, channel::size));
      assertTrue(requested.await(2, TimeUnit.SECONDS));
      channel.close();
      opening.get(2, TimeUnit.SECONDS);
      assertFalse(channel.isOpen());
      assertThrows(IOException.class, channel::size);
    } finally {
      release.countDown();
      server.stop(0);
      workers.shutdownNow();
    }
  }
}
