package indi.mopelotus.musichud.client.audio.decoder.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class MediaTransportFailoverTest {
  @Test
  void stalledIpv4AddressFallsBackToSecondIpv4Socket() throws Exception {
    verifyFailover(InetAddress.getByName("127.0.0.2"));
  }

  @Test
  void stalledIpv6AddressFallsBackToIpv4Socket() throws Exception {
    verifyFailover(InetAddress.getByName("::1"));
  }

  private void verifyFailover(InetAddress first) throws Exception {
    try (Blackhole route = new Blackhole(first)) {
      var resolver = (MediaTransport.Resolver) host -> new InetAddress[] {first, route.fallback};
      try (MediaTransport transport = new MediaTransport(resolver, SSLContext.getDefault(), 400);
          var response =
              transport.open(
                  route.uri(), Map.of(), false, null, new MediaTransport.Cancellation())) {
        assertEquals(
            "second-address", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(1, route.hits.get());
      }
    }
  }

  @Test
  void manyAddressesShareBoundedBudgetWithoutDroppingTheLastAddress() throws Exception {
    try (Blackhole route = new Blackhole(InetAddress.getByName("127.0.0.2"))) {
      InetAddress[] addresses = new InetAddress[21];
      Arrays.fill(addresses, route.first);
      addresses[addresses.length - 1] = route.fallback;
      try (MediaTransport transport =
          new MediaTransport(host -> addresses, SSLContext.getDefault(), 200)) {
        long started = System.nanoTime();
        try (var response =
            transport.open(route.uri(), Map.of(), false, null, new MediaTransport.Cancellation())) {
          assertEquals(
              "second-address", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        // Twenty independent per-address timeouts would require four seconds.
        assertTrue(
            System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2500),
            "Connect stage did not share the six-address budget");
        assertEquals(1, route.hits.get());
      }
    }
  }

  @Test
  void cancellationInterruptsStalledConnectBeforeTheNextAddress() throws Exception {
    try (Blackhole route = new Blackhole(InetAddress.getByName("::1"))) {
      CountDownLatch resolved = new CountDownLatch(1);
      var token = new MediaTransport.Cancellation();
      try (MediaTransport transport =
              new MediaTransport(
                  host -> {
                    resolved.countDown();
                    return new InetAddress[] {route.first, route.fallback};
                  },
                  SSLContext.getDefault(),
                  1000);
          ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> opened =
            caller.submit(
                () -> {
                  assertThrows(
                      IOException.class,
                      () -> transport.open(route.uri(), Map.of(), false, null, token));
                });
        assertTrue(resolved.await(1, TimeUnit.SECONDS));
        Thread.sleep(100); // The fixture has independently proved the first socket cannot connect.
        assertFalse(opened.isDone(), "The connect did not stall");
        long started = System.nanoTime();
        token.cancel();
        opened.get(500, TimeUnit.MILLISECONDS);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500));
        assertEquals(0, route.hits.get(), "Cancellation connected the next address");
      }
    }
  }

  @Test
  void requestFailureIsNotRetriedAgainstAnotherAddress() throws Exception {
    InetAddress first = InetAddress.getByName("127.0.0.2"),
        second = InetAddress.getByName("127.0.0.1");
    HttpServer failing = HttpServer.create(new InetSocketAddress(first, 0), 0);
    HttpServer fallback =
        HttpServer.create(new InetSocketAddress(second, failing.getAddress().getPort()), 0);
    AtomicInteger firstHits = new AtomicInteger(), secondHits = new AtomicInteger();
    failing.createContext(
        "/media",
        exchange -> {
          firstHits.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    fallback.createContext(
        "/media",
        exchange -> {
          secondHits.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    failing.start();
    fallback.start();
    try (MediaTransport transport =
        new MediaTransport(
            host -> new InetAddress[] {first, second}, SSLContext.getDefault(), 500)) {
      URI uri = URI.create("http://media.test:" + failing.getAddress().getPort() + "/media");
      assertThrows(
          IOException.class,
          () -> transport.open(uri, Map.of(), false, null, new MediaTransport.Cancellation()));
      assertEquals(1, firstHits.get());
      assertEquals(0, secondHits.get());
    } finally {
      failing.stop(0);
      fallback.stop(0);
    }
  }

  /** A real filled kernel accept queue drops further SYNs; no mocked connect futures. */
  private static final class Blackhole implements AutoCloseable {
    final InetAddress first, fallback = InetAddress.getByName("127.0.0.1");
    final ServerSocket blocked = new ServerSocket();
    final List<Socket> blockers = new ArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    HttpServer server;

    Blackhole(InetAddress first) throws Exception {
      this.first = first;
      try {
        blocked.bind(new InetSocketAddress(first, 0), 1);
        InetSocketAddress endpoint = new InetSocketAddress(first, blocked.getLocalPort());
        for (int i = 0; i < 2; i++) {
          Socket socket = new Socket();
          blockers.add(socket);
          socket.connect(endpoint, 1000);
        }
        try (Socket probe = new Socket()) {
          assertThrows(
              SocketTimeoutException.class,
              () -> probe.connect(endpoint, 100),
              "The real TCP fixture did not stall");
        }
        server = HttpServer.create(new InetSocketAddress(fallback, blocked.getLocalPort()), 0);
        server.createContext(
            "/media",
            exchange -> {
              hits.incrementAndGet();
              byte[] body = "second-address".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
        server.start();
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    URI uri() {
      return URI.create("http://media.test:" + blocked.getLocalPort() + "/media");
    }

    @Override
    public void close() throws IOException {
      if (server != null) server.stop(0);
      IOException primary = null;
      for (Socket socket : blockers)
        try {
          socket.close();
        } catch (IOException e) {
          if (primary == null) primary = e;
          else primary.addSuppressed(e);
        }
      try {
        blocked.close();
      } catch (IOException e) {
        if (primary == null) primary = e;
        else primary.addSuppressed(e);
      }
      if (primary != null) throw primary;
    }
  }
}
