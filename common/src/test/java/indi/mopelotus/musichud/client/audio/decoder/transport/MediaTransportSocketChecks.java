package indi.mopelotus.musichud.client.audio.decoder.transport;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

final class MediaTransportSocketChecks {
  static int passed;
  static List<String> names = new ArrayList<>();

  interface Checked {
    void run() throws Exception;
  }

  static void test(String name, Checked task) throws Exception {
    task.run();
    passed++;
    names.add(name);
    System.out.println("PASS " + name);
  }

  static void check(boolean c, String m) {
    if (!c) throw new AssertionError(m);
  }

  static void reject(Checked task) throws Exception {
    try {
      task.run();
      throw new AssertionError("Expected rejection");
    } catch (IOException | IllegalArgumentException expected) {
    }
  }

  static byte[] read(
      MediaTransport t, String uri, Map<String, String> h, boolean p, MediaTransport.ByteRange r)
      throws Exception {
    try (var response = t.open(URI.create(uri), h, p, r, new MediaTransport.Cancellation())) {
      return response.body().readAllBytes();
    }
  }

  static String text(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }

  static void send(HttpExchange e, int status, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    e.sendResponseHeaders(status, b.length);
    e.getResponseBody().write(b);
    e.close();
  }

  static void raw(String payload, CheckedUri task) throws Exception {
    try (ServerSocket s = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      Thread server =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket client = s.accept()) {
                      client.getInputStream().readNBytes(1);
                      client.getOutputStream().write(payload.getBytes(StandardCharsets.ISO_8859_1));
                      client.getOutputStream().flush();
                    } catch (Exception ignored) {
                    }
                  });
      task.run("http://media.test:" + s.getLocalPort() + "/raw");
      server.join(2000);
    }
  }

  interface CheckedUri {
    void run(String uri) throws Exception;
  }

  static byte[] compressed(boolean gzip, boolean raw) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (gzip) {
      try (var zip = new java.util.zip.GZIPOutputStream(out)) {
        zip.write("compressed-media".getBytes(StandardCharsets.UTF_8));
      }
    } else {
      java.util.zip.Deflater deflater = new java.util.zip.Deflater(6, raw);
      try (var zip = new java.util.zip.DeflaterOutputStream(out, deflater)) {
        zip.write("compressed-media".getBytes(StandardCharsets.UTF_8));
      } finally {
        deflater.end();
      }
    }
    return out.toByteArray();
  }

  static String compressedResponse(String encoding, byte[] bytes) {
    return "HTTP/1.1 200 OK\r\nContent-Encoding: "
        + encoding
        + "\r\nContent-Length: "
        + bytes.length
        + "\r\n\r\n"
        + new String(bytes, StandardCharsets.ISO_8859_1);
  }

  public static void main(String[] args) throws Exception {
    ExecutorService pool = Executors.newCachedThreadPool();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(pool);
    AtomicInteger hits = new AtomicInteger();
    AtomicReference<Headers> got = new AtomicReference<>();
    CountDownLatch stalled = new CountDownLatch(1);
    CountDownLatch headStalled = new CountDownLatch(1);
    server.createContext(
        "/ok",
        e -> {
          hits.incrementAndGet();
          got.set(e.getRequestHeaders());
          send(e, 200, "media-data");
        });
    server.createContext(
        "/range",
        e -> {
          got.set(e.getRequestHeaders());
          e.getResponseHeaders().set("Content-Range", "bytes 3-6/10");
          send(e, 206, "ia-d");
        });
    server.createContext(
        "/redirect",
        e -> {
          e.getResponseHeaders().set("Location", "/ok");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/cross",
        e -> {
          e.getResponseHeaders()
              .set("Location", "http://other.test:" + server.getAddress().getPort() + "/ok");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/private",
        e -> {
          e.getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/loop",
        e -> {
          e.getResponseHeaders().set("Location", "/loop");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/unsafe",
        e -> {
          e.getResponseHeaders().set("Location", "file:///etc/passwd");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/stall",
        e -> {
          e.sendResponseHeaders(200, 0);
          e.getResponseBody().write(new byte[] {65});
          e.getResponseBody().flush();
          stalled.countDown();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException ignored) {
          }
          e.close();
        });
    server.createContext(
        "/headstall",
        e -> {
          headStalled.countDown();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException ignored) {
          }
          e.close();
        });
    byte[] large = new byte[4 * 1024 * 1024];
    new Random(42).nextBytes(large);
    server.createContext(
        "/large",
        e -> {
          e.sendResponseHeaders(200, large.length);
          try {
            e.getResponseBody().write(large);
          } finally {
            e.close();
          }
        });
    server.createContext(
        "/slow",
        e -> {
          e.sendResponseHeaders(200, 8);
          try {
            for (int i = 0; i < 8; i++) {
              e.getResponseBody().write(65);
              e.getResponseBody().flush();
              Thread.sleep(100);
            }
          } catch (InterruptedException ignored) {
          } finally {
            e.close();
          }
        });
    server.createContext(
        "/origin-redirect",
        e -> {
          e.getResponseHeaders()
              .set(
                  "Location",
                  "http://other.test:" + server.getAddress().getPort() + "/require-origin");
          send(e, 302, "redirect");
        });
    server.createContext(
        "/require-origin",
        e -> {
          boolean valid =
              "https://origin.test".equals(e.getRequestHeaders().getFirst("Origin"))
                  && "https://origin.test".equals(e.getRequestHeaders().getFirst("Referer"));
          send(e, valid ? 200 : 403, valid ? "provider-media" : "denied");
        });
    server.start();
    String base = "http://media.test:" + server.getAddress().getPort();
    AtomicInteger resolutions = new AtomicInteger();
    MediaTransport.Resolver resolver =
        host -> {
          resolutions.incrementAndGet();
          return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
        };
    try (MediaTransport t = new MediaTransport(resolver, SSLContext.getDefault(), 1000)) {
      test(
          "HTTP local LAN permitted",
          () ->
              check(
                  text(read(t, base + "/ok", Map.of(), false, null)).equals("media-data"), "body"));
      test(
          "Exact resolved address connected without second DNS",
          () -> {
            int before = resolutions.get();
            check(text(read(t, base + "/ok", Map.of(), false, null)).equals("media-data"), "body");
            check(resolutions.get() - before == 1, "DNS called more than once");
          });
      test(
          "Public actual resolved private address blocked before socket",
          () -> {
            int before = hits.get();
            reject(
                () ->
                    read(
                        t,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/ok",
                        Map.of(),
                        true,
                        null));
            check(hits.get() == before, "private socket contacted");
          });
      test(
          "Localhost public syntax blocked",
          () ->
              reject(
                  () ->
                      read(
                          t,
                          "http://localhost:" + server.getAddress().getPort() + "/ok",
                          Map.of(),
                          true,
                          null)));
      test(
          "HTTP headers and Host preserved",
          () -> {
            read(
                t,
                base + "/ok",
                Map.of(
                    "User-Agent",
                    "TuneWeave",
                    "Referer",
                    "https://origin.test",
                    "Authorization",
                    "Bearer PRIVATE"),
                false,
                null);
            check(got.get().getFirst("User-Agent").equals("TuneWeave"), "ua");
            check(got.get().getFirst("Host").startsWith("media.test:"), "host");
            check(got.get().getFirst("Authorization").equals("Bearer PRIVATE"), "auth");
          });
      test(
          "Range header and response checked",
          () -> {
            check(
                text(read(
                        t, base + "/range", Map.of(), false, new MediaTransport.ByteRange(3L, 6L)))
                    .equals("ia-d"),
                "rangebody");
            check(got.get().getFirst("Range").equals("bytes=3-6"), "rangeheader");
          });
      test(
          "Ignored seek range rejected",
          () ->
              reject(
                  () ->
                      read(
                          t,
                          base + "/ok",
                          Map.of(),
                          false,
                          new MediaTransport.ByteRange(3L, null))));
      test(
          "Invalid request range rejected",
          () -> {
            reject(() -> new MediaTransport.ByteRange(-1, null));
            reject(() -> new MediaTransport.ByteRange(3, 2L));
          });
      test(
          "Ignored bounded range at zero rejected",
          () ->
              reject(
                  () ->
                      read(
                          t, base + "/ok", Map.of(), false, new MediaTransport.ByteRange(0L, 3L))));
      test(
          "Unsolicited partial rejected",
          () -> reject(() -> read(t, base + "/range", Map.of(), false, null)));
      test(
          "Relative redirect and same origin credentials",
          () -> {
            read(t, base + "/redirect", Map.of("Authorization", "Bearer PRIVATE"), false, null);
            check(
                "Bearer PRIVATE".equals(got.get().getFirst("Authorization")),
                "same origin auth lost");
          });
      test(
          "Cross origin redirect strips credentials and preserves explicit provider headers",
          () -> {
            read(
                t,
                base + "/cross",
                Map.of(
                    "Authorization",
                    "Bearer PRIVATE",
                    "Cookie",
                    "secret",
                    "Origin",
                    "https://origin.test",
                    "Referer",
                    "https://origin.test"),
                false,
                null);
            for (String h : List.of("Authorization", "Cookie"))
              check(got.get().getFirst(h) == null, "leaked " + h);
            for (String h : List.of("Origin", "Referer"))
              check(
                  "https://origin.test".equals(got.get().getFirst(h)), "provider header lost " + h);
          });
      test(
          "Redirect loops bounded",
          () -> reject(() -> read(t, base + "/loop", Map.of(), false, null)));
      test(
          "Non HTTP redirect blocked",
          () -> reject(() -> read(t, base + "/unsafe", Map.of(), false, null)));
      test(
          "URL userinfo rejected",
          () -> reject(() -> read(t, "http://user:pass@media.test/", Map.of(), false, null)));
      test(
          "Header injection rejected",
          () ->
              reject(() -> read(t, base + "/ok", Map.of("User-Agent", "bad\r\nx:1"), false, null)));
      test(
          "Reserved Host and Range rejected",
          () -> {
            reject(() -> read(t, base + "/ok", Map.of("Host", "attacker.test"), false, null));
            reject(() -> read(t, base + "/ok", Map.of("Range", "bytes=1-"), false, null));
          });
      test(
          "Slow consumer bounded queue 4 MiB intact",
          () -> {
            try (var r =
                t.open(
                    URI.create(base + "/large"),
                    Map.of(),
                    false,
                    null,
                    new MediaTransport.Cancellation())) {
              Thread.sleep(80);
              check(Arrays.equals(r.body().readAllBytes(), large), "large corrupt");
            }
          });
      test(
          "Cancel during body unblocks reader",
          () -> {
            var token = new MediaTransport.Cancellation();
            try (var r = t.open(URI.create(base + "/stall"), Map.of(), false, null, token)) {
              check(r.body().read() == 65, "first byte");
              Future<?> wait =
                  pool.submit(
                      () -> {
                        try {
                          r.body().read();
                          throw new AssertionError("Expected cancelled read");
                        } catch (IOException expected) {
                        }
                      });
              Thread.sleep(40);
              long start = System.nanoTime();
              token.cancel();
              wait.get(500, TimeUnit.MILLISECONDS);
              check(System.nanoTime() - start < 500_000_000L, "slow cancel");
            }
          });
      test(
          "Cancel before request",
          () -> {
            var token = new MediaTransport.Cancellation();
            token.cancel();
            reject(() -> t.open(URI.create(base + "/ok"), Map.of(), false, null, token));
          });
      test(
          "Cancel pending response headers",
          () -> {
            var token = new MediaTransport.Cancellation();
            Future<?> wait =
                pool.submit(
                    () -> {
                      try {
                        t.open(URI.create(base + "/headstall"), Map.of(), false, null, token);
                        throw new AssertionError("Expected cancellation");
                      } catch (IOException expected) {
                      }
                    });
            check(headStalled.await(1, TimeUnit.SECONDS), "server not hit");
            token.cancel();
            wait.get(500, TimeUnit.MILLISECONDS);
          });
      test(
          "Malformed content range rejected",
          () ->
              raw(
                  "HTTP/1.1 206 Partial Content\r\n"
                      + "Content-Length: 4\r\n"
                      + "Content-Range: bytes 3-99/10\r\n\r\n"
                      + "oops",
                  uri ->
                      reject(
                          () ->
                              read(
                                  t, uri, Map.of(), false, new MediaTransport.ByteRange(3L, 6L)))));
      test(
          "Truncated body rejected",
          () ->
              raw(
                  "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx",
                  uri -> reject(() -> read(t, uri, Map.of(), false, null))));
      test(
          "Malformed chunk rejected",
          () ->
              raw(
                  "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nxyz\r\nx\r\n0\r\n\r\n",
                  uri -> reject(() -> read(t, uri, Map.of(), false, null))));
      test(
          "Informational response then final body",
          () ->
              raw(
                  "HTTP/1.1 103 Early Hints\r\n"
                      + "Link: </x>\r\n\r\n"
                      + "HTTP/1.1 200 OK\r\n"
                      + "Content-Length: 2\r\n\r\n"
                      + "OK",
                  uri -> check(text(read(t, uri, Map.of(), false, null)).equals("OK"), "interim")));
      test(
          "Valid chunked body",
          () ->
              raw(
                  "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nOK\r\n0\r\n\r\n",
                  uri -> check(text(read(t, uri, Map.of(), false, null)).equals("OK"), "chunked")));
      test(
          "Each redirect resolves separately",
          () -> {
            int before = resolutions.get();
            read(t, base + "/redirect", Map.of(), false, null);
            check(resolutions.get() - before == 2, "redirect reused unvalidated DNS");
          });
      test(
          "Rebinding resolver result pinned to first actual socket",
          () -> {
            AtomicInteger calls = new AtomicInteger();
            try (var flip =
                new MediaTransport(
                    host ->
                        new InetAddress[] {
                          InetAddress.getByAddress(
                              host,
                              new byte[] {127, 0, 0, (byte) (calls.incrementAndGet() == 1 ? 1 : 2)})
                        },
                    SSLContext.getDefault(),
                    300)) {
              check(
                  text(read(flip, base + "/ok", Map.of(), false, null)).equals("media-data"),
                  "rebinding actual socket");
              check(calls.get() == 1, "second DNS lookup");
            }
          });
      test(
          "One cancellation closes parallel segment streams",
          () -> {
            var token = new MediaTransport.Cancellation();
            try (var a = t.open(URI.create(base + "/stall"), Map.of(), false, null, token);
                var b = t.open(URI.create(base + "/stall"), Map.of(), false, null, token)) {
              check(a.body().read() == 65 && b.body().read() == 65, "first bytes");
              List<Future<?>> waits = new ArrayList<>();
              for (var r : List.of(a, b))
                waits.add(
                    pool.submit(
                        () -> {
                          try {
                            r.body().read();
                            throw new AssertionError("Expected cancellation");
                          } catch (IOException expected) {
                          }
                        }));
              Thread.sleep(30);
              token.cancel();
              for (Future<?> wait : waits) wait.get(500, TimeUnit.MILLISECONDS);
            }
          });
      test(
          "DNS phase timeout bounded",
          () -> {
            CountDownLatch release = new CountDownLatch(1);
            try (var dns =
                new MediaTransport(
                    host -> {
                      while (true) {
                        try {
                          release.await();
                          break;
                        } catch (InterruptedException ignored) {
                        }
                      }
                      return new InetAddress[] {InetAddress.getLoopbackAddress()};
                    },
                    SSLContext.getDefault(),
                    100)) {
              long start = System.nanoTime();
              reject(() -> read(dns, base + "/ok", Map.of(), false, null));
              check(System.nanoTime() - start < 500_000_000L, "DNS timeout blocked by OS call");
            } finally {
              release.countDown();
            }
          });
      test(
          "Cancel DNS wait promptly",
          () -> {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var dns =
                new MediaTransport(
                    host -> {
                      entered.countDown();
                      while (true) {
                        try {
                          release.await();
                          break;
                        } catch (InterruptedException ignored) {
                        }
                      }
                      return new InetAddress[] {InetAddress.getLoopbackAddress()};
                    },
                    SSLContext.getDefault(),
                    1000)) {
              var token = new MediaTransport.Cancellation();
              Future<?> waiter =
                  pool.submit(
                      () -> {
                        try {
                          dns.open(URI.create(base + "/ok"), Map.of(), false, null, token);
                          throw new AssertionError("DNS cancellation not thrown");
                        } catch (IOException expected) {
                        }
                      });
              check(entered.await(1, TimeUnit.SECONDS), "DNS not entered");
              token.cancel();
              waiter.get(500, TimeUnit.MILLISECONDS);
            } finally {
              release.countDown();
            }
          });
      test(
          "Blocked OS DNS worker and queue capacity bounded",
          () -> {
            AtomicInteger active = new AtomicInteger(), capacityFailures = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            try (var dns =
                new MediaTransport(
                    host -> {
                      active.incrementAndGet();
                      while (true) {
                        try {
                          release.await();
                          break;
                        } catch (InterruptedException ignored) {
                        }
                      }
                      return new InetAddress[] {InetAddress.getLoopbackAddress()};
                    },
                    SSLContext.getDefault(),
                    250)) {
              List<Future<?>> calls = new ArrayList<>();
              for (int i = 0; i < 50; i++)
                calls.add(
                    pool.submit(
                        () -> {
                          try {
                            dns.open(
                                URI.create(base + "/ok"),
                                Map.of(),
                                false,
                                null,
                                new MediaTransport.Cancellation());
                            throw new AssertionError("DNS stall succeeded");
                          } catch (IOException expected) {
                            if (expected.getMessage().contains("capacity"))
                              capacityFailures.incrementAndGet();
                          }
                        }));
              for (Future<?> call : calls) call.get(1, TimeUnit.SECONDS);
              check(active.get() == 2, "OS DNS worker count not capped");
              check(capacityFailures.get() > 0, "DNS overload not bounded");
            } finally {
              release.countDown();
            }
          });
      test(
          "gzip transparent decoding",
          () ->
              raw(
                  compressedResponse("gzip", compressed(true, false)),
                  uri ->
                      check(
                          text(read(t, uri, Map.of(), false, null)).equals("compressed-media"),
                          "gzip")));
      test(
          "zlib deflate transparent decoding",
          () ->
              raw(
                  compressedResponse("deflate", compressed(false, false)),
                  uri ->
                      check(
                          text(read(t, uri, Map.of(), false, null)).equals("compressed-media"),
                          "deflate")));
      test(
          "raw deflate transparent decoding",
          () ->
              raw(
                  compressedResponse("deflate", compressed(false, true)),
                  uri ->
                      check(
                          text(read(t, uri, Map.of(), false, null)).equals("compressed-media"),
                          "raw deflate")));
      test(
          "malformed gzip body fails",
          () ->
              raw(
                  "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n\r\nbad",
                  uri -> reject(() -> read(t, uri, Map.of(), false, null))));
      test(
          "long flowing body exceeds opening deadline",
          () -> {
            try (var flowing = new MediaTransport(resolver, SSLContext.getDefault(), 400)) {
              try (var response =
                  flowing.open(
                      URI.create(base + "/slow"),
                      Map.of(),
                      false,
                      null,
                      new MediaTransport.Cancellation())) {
                check(response.body().readAllBytes().length == 8, "long body truncated");
              }
            }
          });
      test(
          "CDN redirect requiring explicit Origin and Referer",
          () ->
              check(
                  text(read(
                          t,
                          base + "/origin-redirect",
                          Map.of("Origin", "https://origin.test", "Referer", "https://origin.test"),
                          false,
                          null))
                      .equals("provider-media"),
                  "CDN provider headers"));
      test(
          "Neutral default user agent",
          () -> {
            read(t, base + "/ok", Map.of(), false, null);
            check(
                "MusicHud-TuneWeave".equals(got.get().getFirst("User-Agent")),
                "default user agent");
          });
      test(
          "Decoded compression length remains unknown",
          () ->
              raw(
                  compressedResponse("gzip", compressed(true, false)),
                  uri -> {
                    try (var response =
                        t.open(
                            URI.create(uri),
                            Map.of(),
                            false,
                            null,
                            new MediaTransport.Cancellation())) {
                      check(response.length() == -1, "compressed size exposed as decoded length");
                      check(
                          text(response.body().readAllBytes()).equals("compressed-media"),
                          "decoded bytes");
                    }
                  }));
      test(
          "Compressed range rejected",
          () ->
              raw(
                  "HTTP/1.1 206 Partial Content\r\n"
                      + "Content-Encoding: gzip\r\n"
                      + "Content-Range: bytes 3-6/10\r\n"
                      + "Content-Length: 4\r\n\r\n"
                      + "bad!",
                  uri ->
                      reject(
                          () ->
                              read(
                                  t, uri, Map.of(), false, new MediaTransport.ByteRange(3L, 6L)))));
      test(
          "Read timeout closes stream",
          () -> {
            try (MediaTransport shortT =
                new MediaTransport(resolver, SSLContext.getDefault(), 150)) {
              reject(() -> read(shortT, base + "/stall", Map.of(), false, null));
            }
          });
    } finally {
      server.stop(0);
      pool.shutdownNow();
    }
    System.out.println("TOTAL " + passed);
    Files.writeString(
        Path.of(args[0]),
        "{\"java\":\""
            + System.getProperty("java.version")
            + "\",\"passed\":"
            + passed
            + ",\"tests\":["
            + String.join(",", names.stream().map(s -> "\"" + s + "\"").toList())
            + "]}");
  }
}
