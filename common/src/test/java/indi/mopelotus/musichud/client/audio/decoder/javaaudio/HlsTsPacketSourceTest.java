package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.container.input.*;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HlsTsPacketSourceTest {
  static final Path FIXTURES = fixtures();
  @org.junit.jupiter.api.io.TempDir static Path temporary;
  static Path OUTPUT;

  @org.junit.jupiter.api.BeforeAll
  static void initializeOutput() throws IOException {
    OUTPUT =
        System.getProperty("audio.test.output") == null
            ? temporary
            : Path.of(System.getProperty("audio.test.output"));
    Files.createDirectories(OUTPUT);
  }

  private static Path fixtures() {
    if (System.getProperty("audio.test.fixtures") != null)
      return Path.of(System.getProperty("audio.test.fixtures"));
    try {
      return Path.of(
              Objects.requireNonNull(
                      HlsTsPacketSourceTest.class.getResource("/hls-timeline/audio.m3u8"))
                  .toURI())
          .getParent();
    } catch (Exception failure) {
      throw new IllegalStateException("Missing HLS test resources", failure);
    }
  }

  static PacketSource packets(URI uri, TransportHlsFetcher fetcher) throws IOException {
    return new HlsPacketSource(new HlsPlaybackInput(uri, false, null, fetcher));
  }

  static byte[] decode(URI uri, TransportHlsFetcher fetcher) throws IOException {
    try (var source = new PacketMediaDecoder(packets(uri, fetcher))) {
      assertEquals(48000, source.format().sampleRate());
      assertEquals(2, source.format().channels());
      assertEquals(PcmFormat.Encoding.F32LE, source.format().encoding());
      var out = new ByteArrayOutputStream();
      byte[] b = new byte[4096 * source.format().frameBytes()];
      for (int n; (n = source.readFrames(b, 0, 4096)) >= 0; )
        out.write(b, 0, n * source.format().frameBytes());
      return out.toByteArray();
    }
  }

  static final class Server implements AutoCloseable {
    final HttpServer server;
    final ExecutorService pool = Executors.newCachedThreadPool();

    Server(HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(pool);
      server.createContext(
          "/",
          exchange -> {
            try {
              handler.handle(exchange);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    URI uri(String path) {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public void close() {
      server.stop(0);
      pool.shutdownNow();
    }
  }

  static void file(HttpExchange exchange) throws IOException {
    Path p = FIXTURES.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
    if (!p.startsWith(FIXTURES)) {
      exchange.sendResponseHeaders(400, -1);
      return;
    }
    byte[] b = Files.readAllBytes(p);
    exchange.sendResponseHeaders(200, b.length);
    exchange.getResponseBody().write(b);
  }

  static PresentedDecoder.Factory factory(URI uri, TransportHlsFetcher fetcher) {
    return () ->
        new PresentedDecoder.Attempt() {
          private final Object state = new Object();
          private AutoCloseable owner;
          private boolean cancelled;

          private <T extends AutoCloseable> T attach(T value) throws IOException {
            synchronized (state) {
              if (!cancelled) {
                owner = value;
                return value;
              }
            }
            try {
              value.close();
            } catch (Exception ignored) {
            }
            throw new IOException("cancelled open");
          }

          public PcmDecoder open() throws IOException {
            var input = attach(new HlsPlaybackInput(uri, false, null, fetcher));
            var source = attach(new HlsPacketSource(input));
            return attach(new PacketMediaDecoder(source));
          }

          public void cancel() {
            AutoCloseable old;
            synchronized (state) {
              cancelled = true;
              old = owner;
              owner = null;
            }
            if (old != null)
              try {
                old.close();
              } catch (Exception ignored) {
              }
          }
        };
  }

  @Test
  void standaloneTsUsesTheSameQuantizedOriginClockAndCompletePcm() throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      byte[] expected = decode(FIXTURES.resolve("standalone-ts-same-route.m3u8").toUri(), fetcher);
      var source =
          new indi.mopelotus.musichud.audio.container.aac.TsAdtsPacketSource(
              new indi.mopelotus.musichud.audio.container.ts.TsPacketSource(
                  Files.newInputStream(FIXTURES.resolve("aac.ts"))));
      try (var decoder =
          new PacketMediaDecoder(
              new NormalizedPacketSource(source, () -> 0, new PacketSource.Time(1, 90000)))) {
        var out = new ByteArrayOutputStream();
        byte[] block = new byte[4096 * decoder.format().frameBytes()];
        for (int n; (n = decoder.readFrames(block, 0, 4096)) >= 0; )
          out.write(block, 0, n * decoder.format().frameBytes());
        assertArrayEquals(expected, out.toByteArray());
      }
    }
  }

  @Test
  void actualHlsPacketsPcmAndHttpMatchLocal() throws Exception {
    try (MediaTransport transport = new MediaTransport();
        Server server = new Server(HlsTsPacketSourceTest::file)) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      byte[] local = decode(FIXTURES.resolve("audio.m3u8").toUri(), fetcher),
          http = decode(server.uri("/audio.m3u8"), fetcher);
      assertArrayEquals(local, http);
      assertTrue(local.length > 1_000_000);
      Files.createDirectories(OUTPUT);
      Files.write(OUTPUT.resolve("hls-full.f32"), local);
      try (var source = packets(server.uri("/audio.m3u8"), fetcher)) {
        var first = source.next();
        assertEquals(0, first.presentationTime().ticks());
        assertTrue(source.track().edits().isEmpty());
        PacketSource.Time previousEnd = null;
        StringBuilder timeline =
            new StringBuilder(
                "index,pts_ticks,pts_scale,duration_ticks,duration_scale,discontinuity\n");
        int count = 0;
        for (var p = first; p != null; p = source.next()) {
          if (previousEnd != null) assertTrue(p.presentationTime().compareValue(previousEnd) >= 0);
          previousEnd = add(p.presentationTime(), p.duration());
          timeline
              .append(count)
              .append(',')
              .append(p.presentationTime().ticks())
              .append(',')
              .append(p.presentationTime().timescale())
              .append(',')
              .append(p.duration().ticks())
              .append(',')
              .append(p.duration().timescale())
              .append(',')
              .append(p.discontinuity())
              .append('\n');
          count++;
        }
        assertTrue(count > 200);
        Files.writeString(OUTPUT.resolve("hls-timeline.csv"), timeline);
      }
    }
  }

  static PacketSource.Time add(PacketSource.Time a, PacketSource.Time b) {
    java.math.BigInteger
        n =
            java.math.BigInteger.valueOf(a.ticks())
                .multiply(java.math.BigInteger.valueOf(b.timescale()))
                .add(
                    java.math.BigInteger.valueOf(b.ticks())
                        .multiply(java.math.BigInteger.valueOf(a.timescale()))),
        d =
            java.math.BigInteger.valueOf(a.timescale())
                .multiply(java.math.BigInteger.valueOf(b.timescale())),
        g = n.gcd(d);
    return new PacketSource.Time(n.divide(g).longValueExact(), d.divide(g).longValueExact());
  }

  @Test
  void exactReopenSeekMatchesCompletePcmAfterForwardAndBackwardMoves() throws Exception {
    try (MediaTransport transport = new MediaTransport();
        Server server = new Server(HlsTsPacketSourceTest::file)) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      URI uri = server.uri("/audio.m3u8");
      byte[] full = decode(uri, fetcher);
      long frames = full.length / 8;
      try (var source =
          new PresentedDecoder(
              factory(uri, fetcher),
              new PresentationPlan(
                  List.of(new PresentationPlan.Segment(0, Long.MAX_VALUE)), Long.MAX_VALUE))) {
        for (long target : new long[] {0, 17, frames - 73, 2000, frames / 2, 0}) {
          assertTrue(source.seekFrame(target));
          byte[] block = new byte[51 * 8];
          int read = 0;
          while (read < 51) {
            int n = source.readFrames(block, read * 8, 51 - read);
            if (n < 0) break;
            read += n;
          }
          assertEquals(51, read);
          assertArrayEquals(
              Arrays.copyOfRange(
                  full, Math.toIntExact(target * 8), Math.toIntExact((target + read) * 8)),
              block);
          assertEquals(target + read, source.framePosition());
        }
      }
    }
  }

  @Test
  void genuineHlsEpochJoinsExactPcmEndWithoutOriginSilence() throws Exception {
    try (MediaTransport transport = new MediaTransport();
        Server server =
            new Server(
                exchange -> {
                  String path = exchange.getRequestURI().getPath();
                  if (path.endsWith(".m3u8")) {
                    String text =
                        "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:7\n#EXTINF:1,\naac.ts\n"
                            + (path.equals("/twice.m3u8")
                                ? "#EXT-X-DISCONTINUITY\n#EXTINF:1,\naac.ts\n"
                                : "")
                            + "#EXT-X-ENDLIST";
                    byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, b.length);
                    exchange.getResponseBody().write(b);
                  } else file(exchange);
                })) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      byte[] one = decode(server.uri("/once.m3u8"), fetcher),
          twice = decode(server.uri("/twice.m3u8"), fetcher);
      var out = new ByteArrayOutputStream();
      out.write(one);
      out.write(one);
      assertArrayEquals(out.toByteArray(), twice);
    }
  }

  @Test
  void closeCancelsActualHlsLiveReloadAndRejectsLateOutput() throws Exception {
    AtomicInteger manifests = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (MediaTransport transport = new MediaTransport();
        Server server =
            new Server(
                exchange -> {
                  if (!exchange.getRequestURI().getPath().equals("/live.m3u8")) {
                    file(exchange);
                    return;
                  }
                  if (manifests.incrementAndGet() == 1) {
                    byte[] b = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\naac.ts\n".getBytes();
                    exchange.sendResponseHeaders(200, b.length);
                    exchange.getResponseBody().write(b);
                  } else {
                    exchange.sendResponseHeaders(200, 1000);
                    exchange.getResponseBody().write('#');
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    try {
                      release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                });
        var pool = Executors.newSingleThreadExecutor()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      try (var source = packets(server.uri("/live.m3u8"), fetcher)) {
        var future =
            pool.submit(
                () -> {
                  try {
                    while (source.next() != null) {}
                    throw new AssertionError("live unexpectedly ended");
                  } catch (IOException expected) {
                    return true;
                  }
                });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        source.close();
        assertTrue(future.get(2, TimeUnit.SECONDS));
        assertThrows(IOException.class, source::next);
      }
    } finally {
      release.countDown();
    }
  }
}
