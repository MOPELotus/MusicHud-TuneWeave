package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import indi.mopelotus.musichud.audio.container.hls.HlsManifest;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class TransportHlsFetcherTest {
  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  static final class Server implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newCachedThreadPool();
    final AtomicInteger requests = new AtomicInteger();
    final List<String> ranges = Collections.synchronizedList(new ArrayList<>());
    final List<String> ifRanges = Collections.synchronizedList(new ArrayList<>());

    Server(HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            ranges.add(exchange.getRequestHeaders().getFirst("Range"));
            ifRanges.add(exchange.getRequestHeaders().getFirst("If-Range"));
            try {
              handler.handle(exchange);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media");
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void requested206AndIgnored200ReturnOnlyOneCompleteResponseSlice() throws Exception {
    for (boolean honor : List.of(false, true))
      try (Server server =
              new Server(
                  exchange -> {
                    byte[] body = bytes(honor ? "efgh" : "abcdefghij");
                    if (honor) exchange.getResponseHeaders().add("Content-Range", "bytes 4-7/10");
                    exchange.sendResponseHeaders(honor ? 206 : 200, body.length);
                    exchange.getResponseBody().write(body);
                  });
          MediaTransport transport = new MediaTransport()) {
        var fetcher = new TransportHlsFetcher(transport, uri -> Map.of("X-Input", "hls"));
        try (var request = fetcher.request(server.uri(), new HlsManifest.Range(4, 4), false);
            var response = request.open()) {
          assertArrayEquals(bytes("efgh"), response.body().readAllBytes());
          assertEquals(-1, response.body().read());
          assertEquals(server.uri(), response.finalUri());
        }
        assertEquals(honor ? 1 : 2, server.requests.get());
        assertEquals(
            honor ? List.of("bytes=4-7") : Arrays.asList("bytes=4-7", null), server.ranges);
        assertTrue(server.ifRanges.stream().allMatch(Objects::isNull));
      }
  }

  @Test
  void ignoredRangeWithShortSkipAndShortSliceFailsInsteadOfAcceptingPartial() throws Exception {
    for (HlsManifest.Range range :
        List.of(new HlsManifest.Range(20, 3), new HlsManifest.Range(4, 10)))
      try (Server server =
              new Server(
                  exchange -> {
                    byte[] body = bytes("abcdefghij");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                  });
          MediaTransport transport = new MediaTransport()) {
        var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
        try (var request = fetcher.request(server.uri(), range, false)) {
          assertThrows(
              IOException.class,
              () -> {
                try (var response = request.open()) {
                  response.body().readAllBytes();
                }
              });
        }
        assertEquals(2, server.requests.get());
      }
  }

  @Test
  void closeCancelsBlockedIgnoredRangeSkip() throws Exception {
    CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
    try (Server server =
            new Server(
                exchange -> {
                  if (exchange.getRequestHeaders().containsKey("Range")) {
                    exchange.sendResponseHeaders(200, 10);
                    exchange.getResponseBody().write(bytes("abcdefghij"));
                    return;
                  }
                  exchange.sendResponseHeaders(200, 100);
                  exchange.getResponseBody().write(bytes("abc"));
                  exchange.getResponseBody().flush();
                  sent.countDown();
                  try {
                    release.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
        MediaTransport transport = new MediaTransport();
        var executor = Executors.newSingleThreadExecutor()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      var request = fetcher.request(server.uri(), new HlsManifest.Range(20, 3), false);
      var result =
          executor.submit(
              () -> {
                try (var response = request.open()) {
                  throw new AssertionError("Cancelled skip unexpectedly opened");
                } catch (IOException expected) {
                  return true;
                }
              });
      assertTrue(sent.await(2, TimeUnit.SECONDS));
      request.close();
      assertTrue(result.get(2, TimeUnit.SECONDS));
      assertEquals(2, server.requests.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void closedRequestCannotOpenLater() throws Exception {
    try (Server server =
            new Server(
                exchange -> {
                  throw new AssertionError("late request reached server");
                });
        MediaTransport transport = new MediaTransport()) {
      var request =
          new TransportHlsFetcher(transport, uri -> Map.of())
              .request(server.uri(), new HlsManifest.Range(0, 1), false);
      request.close();
      assertThrows(IOException.class, request::open);
      assertEquals(0, server.requests.get());
    }
  }

  @Test
  void headerSelectionUsesExactTargetUri() throws Exception {
    List<URI> selected = new ArrayList<>();
    AtomicInteger leaked = new AtomicInteger();
    try (Server first =
            new Server(
                exchange -> {
                  exchange.sendResponseHeaders(200, 1);
                  exchange.getResponseBody().write('a');
                });
        Server second =
            new Server(
                exchange -> {
                  if (exchange.getRequestHeaders().containsKey("Authorization"))
                    leaked.incrementAndGet();
                  exchange.sendResponseHeaders(200, 1);
                  exchange.getResponseBody().write('b');
                });
        MediaTransport transport = new MediaTransport()) {
      var fetcher =
          new TransportHlsFetcher(
              transport,
              uri -> {
                selected.add(uri);
                return uri.getPort() == first.uri().getPort()
                    ? Map.of("Authorization", "fixture-only")
                    : Map.of();
              });
      for (URI uri : List.of(first.uri(), second.uri()))
        try (var request = fetcher.request(uri, null, false);
            var resource = request.open()) {
          assertEquals(1, resource.body().readAllBytes().length);
        }
      assertEquals(List.of(first.uri(), second.uri()), selected);
      assertEquals(0, leaked.get());
    }
  }
}
