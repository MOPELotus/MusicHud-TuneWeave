package indi.mopelotus.musichud.client.audio.decoder.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.*;

final class IcyTransportChecks {
  static int checks;

  interface Checked {
    void run() throws Exception;
  }

  static void check(boolean valid, String message) {
    checks++;
    if (!valid) throw new AssertionError(message);
  }

  static void reject(Checked action) throws Exception {
    try {
      action.run();
      throw new AssertionError("Invalid ICY response accepted");
    } catch (IOException expected) {
      checks++;
    }
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  interface Exchange {
    void run(URI uri, AtomicReference<String> request) throws Exception;
  }

  static void socket(byte[] response, boolean fragmented, SSLContext tls, Exchange exchange)
      throws Exception {
    try (ServerSocket server =
        tls == null ? new ServerSocket() : tls.getServerSocketFactory().createServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      AtomicReference<String> request = new AtomicReference<>();
      CompletableFuture<Void> writer = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.setSoTimeout(3000);
                  ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                  int matched = 0;
                  while (matched < 4) {
                    int b = socket.getInputStream().read();
                    if (b < 0) throw new EOFException();
                    bytes.write(b);
                    matched =
                        (matched == 0 || matched == 2)
                            ? (b == 13 ? matched + 1 : 0)
                            : (b == 10 ? matched + 1 : 0);
                  }
                  request.set(bytes.toString(StandardCharsets.ISO_8859_1));
                  if (fragmented) {
                    for (int i = 0; i < Math.min(response.length, 24); i++) {
                      socket.getOutputStream().write(response[i]);
                      socket.getOutputStream().flush();
                      Thread.sleep(1);
                    }
                    socket
                        .getOutputStream()
                        .write(
                            response,
                            Math.min(response.length, 24),
                            Math.max(0, response.length - 24));
                  } else socket.getOutputStream().write(response);
                  socket.getOutputStream().flush();
                  writer.complete(null);
                } catch (Throwable failure) {
                  writer.completeExceptionally(failure);
                }
              });
      try {
        exchange.run(
            URI.create(
                (tls == null ? "http" : "https")
                    + "://media.test:"
                    + server.getLocalPort()
                    + "/radio"),
            request);
      } finally {
        try {
          writer.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
          if (!(expected.getCause() instanceof IOException)) throw expected;
        }
      }
    }
  }

  static MediaTransport transport(SSLContext tls) throws Exception {
    return new MediaTransport(
        host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")}, tls, 2000);
  }

  static byte[] response(String status, String headers, byte[] body) {
    return concat(
        ascii(
            status
                + "\r\nContent-Length: "
                + body.length
                + "\r\n"
                + headers
                + "Connection: close\r\n\r\n"),
        body);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] all = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, all, a.length, b.length);
    return all;
  }

  static byte[] normalized(String original, int split) throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel(new IcyStatusNormalizer());
    try {
      byte[] bytes = ascii(original);
      channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 0, split)));
      channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, split, bytes.length)));
      channel.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteBuf part;
      while ((part = channel.readInbound()) != null) {
        try {
          byte[] data = new byte[part.readableBytes()];
          part.readBytes(data);
          out.write(data);
        } finally {
          part.release();
        }
      }
      return out.toByteArray();
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  static SSLContext[] tls(Path dir) throws Exception {
    Path store = Files.createTempDirectory(dir, "tls-").resolve("synthetic-icy-only.p12");
    String tool = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", tool).toString(),
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=media.test",
                "-ext",
                "SAN=dns:media.test",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "test-only-password",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("keytool.log").toFile())
            .start();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IOException("Synthetic certificate generation failed");
    } finally {
      process.destroyForcibly();
    }
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(store)) {
      keys.load(input, "test-only-password".toCharArray());
    }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, "test-only-password".toCharArray());
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(keys);
    SSLContext server = SSLContext.getInstance("TLS"), client = SSLContext.getInstance("TLS");
    server.init(km.getKeyManagers(), null, null);
    client.init(null, tm.getTrustManagers(), null);
    return new SSLContext[] {server, client};
  }

  static void cancelIncompletePrefix(boolean closeClient) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var client = transport(SSLContext.getDefault())) {
      CountDownLatch prefixSent = new CountDownLatch(1);
      CompletableFuture<Void> disconnected = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try (Socket peer = server.accept()) {
                  peer.setSoTimeout(3000);
                  // Read the complete request before stalling halfway through the response prefix.
                  int matched = 0;
                  while (matched < 4) {
                    int b = peer.getInputStream().read();
                    if (b < 0) throw new EOFException();
                    matched =
                        (matched == 0 || matched == 2)
                            ? (b == 13 ? matched + 1 : 0)
                            : (b == 10 ? matched + 1 : 0);
                  }
                  peer.getOutputStream().write(ascii("ICY "));
                  peer.getOutputStream().flush();
                  prefixSent.countDown();
                  check(peer.getInputStream().read() == -1, "Cancelled prefix connection retained");
                  disconnected.complete(null);
                } catch (Throwable failure) {
                  disconnected.completeExceptionally(failure);
                }
              });
      var cancellation = new MediaTransport.Cancellation();
      CompletableFuture<Void> opened = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  client.open(
                      URI.create("http://media.test:" + server.getLocalPort() + "/radio"),
                      Map.of(),
                      false,
                      null,
                      cancellation);
                  opened.completeExceptionally(new AssertionError("Incomplete status accepted"));
                } catch (IOException expected) {
                  opened.complete(null);
                } catch (Throwable failure) {
                  opened.completeExceptionally(failure);
                }
              });
      check(prefixSent.await(2, TimeUnit.SECONDS), "Prefix not sent");
      long start = System.nanoTime();
      if (closeClient) client.close();
      else cancellation.cancel();
      opened.get(1, TimeUnit.SECONDS);
      disconnected.get(1, TimeUnit.SECONDS);
      check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "Slow prefix cancellation");
    }
  }

  public static void main(String[] args) throws Exception {
    checks = 0;
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    String line = "ICY 200 OK\r\nContent-Length: 3\r\n\r\nabc";
    for (int split = 1; split < line.length(); split++)
      check(
          Arrays.equals(normalized(line, split), ascii(line.replaceFirst("ICY ", "HTTP/1.0 "))),
          "fragment status normalization");
    for (String unchanged :
        List.of(
            "HTTP/1.1 200 OK\r\n\r\nICY 200 body",
            "ICYS 200 OK\r\n\r\n",
            "ICY 0200 OK\r\n\r\n",
            "ICY 20 OK\r\n\r\n",
            "ICY 200X nope\r\n\r\n"))
      check(
          Arrays.equals(normalized(unchanged, 2), ascii(unchanged)), "Unexpected protocol rewrite");
    SSLContext defaults = SSLContext.getDefault();
    for (String status : List.of("HTTP/1.1 200 OK", "ICY 200 OK")) {
      socket(
          response(status, "icy-metaint: 2\r\nETag: \"static\"\r\n", ascii("ab\0cd")),
          true,
          null,
          (uri, request) -> {
            try (var client = transport(defaults);
                var result =
                    client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation())) {
              check(result.status() == 200, "status");
              check(result.icyMetaInt() == 2, "metadata interval");
              check(
                  Arrays.equals(result.body().readAllBytes(), ascii("ab\0cd")),
                  "body must remain interleaved");
              check(
                  result.length() == -1 && result.representation().totalLength() == -1,
                  "metadata length must be unknown");
              check(
                  !result.representation().canRange(),
                  "metadata must not enable typed cache ranges");
              check(
                  !request.get().toLowerCase(Locale.ROOT).contains("icy-metadata:"),
                  "metadata unexpectedly requested");
            }
          });
    }
    socket(
        response("ICY 200 OK", "", ascii("ICY 200 payload")),
        false,
        null,
        (uri, request) -> {
          try (var client = transport(defaults);
              var result =
                  client.open(
                      uri,
                      Map.of("Icy-MetaData", "1"),
                      false,
                      null,
                      new MediaTransport.Cancellation())) {
            check(result.icyMetaInt() == 0, "absent interval");
            check(
                new String(result.body().readAllBytes(), StandardCharsets.ISO_8859_1)
                    .equals("ICY 200 payload"),
                "payload rewritten");
            check(
                request.get().toLowerCase(Locale.ROOT).contains("icy-metadata: 1"),
                "explicit caller header lost");
          }
        });
    for (String invalid :
        List.of(
            "ICYS 200 OK", "icy 200 OK", "ICY 0200 OK", "ICY 20 OK", "ICY 200X OK", "ICY 999 Bad"))
      socket(
          response(invalid, "", ascii("abc")),
          true,
          null,
          (uri, request) -> {
            try (var client = transport(defaults)) {
              reject(
                  () -> client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation()));
            }
          });
    for (String interval :
        List.of("", "0", "-1", "+1", "1x", "16777217", "999999999999999999999999", "2, 2"))
      socket(
          response("ICY 200 OK", "icy-metaint: " + interval + "\r\n", ascii("abc")),
          false,
          null,
          (uri, request) -> {
            try (var client = transport(defaults)) {
              reject(
                  () -> client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation()));
            }
          });
    socket(
        response("ICY 200 OK", "icy-metaint: 2\r\nicy-metaint: 2\r\n", ascii("abc")),
        false,
        null,
        (uri, request) -> {
          try (var client = transport(defaults)) {
            reject(
                () -> client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation()));
          }
        });
    socket(
        response("ICY 200 OK", "icy-metaint: 16777216\r\n", ascii("abc")),
        false,
        null,
        (uri, request) -> {
          try (var client = transport(defaults);
              var result =
                  client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation())) {
            check(result.icyMetaInt() == 16777216, "maximum valid interval");
            result.body().readAllBytes();
          }
        });
    socket(
        response(
            "HTTP/1.1 206 Partial Content",
            "Content-Range: bytes 1-2/3\r\nicy-metaint: 2\r\n",
            ascii("bc")),
        false,
        null,
        (uri, request) -> {
          try (var client = transport(defaults)) {
            reject(
                () ->
                    client.open(
                        uri,
                        Map.of(),
                        false,
                        new MediaTransport.ByteRange(1L, 2L),
                        new MediaTransport.Cancellation()));
          }
        });
    ByteArrayOutputStream zipped = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(zipped)) {
      gzip.write(ascii("ab\0cd"));
    }
    socket(
        response(
            "ICY 200 OK", "Content-Encoding: gzip\r\nicy-metaint: 2\r\n", zipped.toByteArray()),
        true,
        null,
        (uri, request) -> {
          try (var client = transport(defaults);
              var result =
                  client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation())) {
            check(result.icyMetaInt() == 2, "decoded metadata interval");
            check(
                Arrays.equals(result.body().readAllBytes(), ascii("ab\0cd")),
                "gzip must decode before metadata stripping");
          }
        });
    cancelIncompletePrefix(false);
    cancelIncompletePrefix(true);
    var pair = tls(directory);
    socket(
        response("ICY 200 OK", "icy-metaint: 2\r\n", ascii("ab\0cd")),
        true,
        pair[0],
        (uri, request) -> {
          try (var client = transport(pair[1]);
              var result =
                  client.open(uri, Map.of(), false, null, new MediaTransport.Cancellation())) {
            check(result.icyMetaInt() == 2, "TLS ICY header");
            check(Arrays.equals(result.body().readAllBytes(), ascii("ab\0cd")), "TLS ICY body");
          }
        });
    socket(
        response(
            "HTTP/1.1 206 Partial Content",
            "Content-Range: bytes 0-2/3\r\nETag: \"static\"\r\nicy-metaint: 2\r\n",
            ascii("abc")),
        false,
        null,
        (uri, request) -> {
          var expected =
              new MediaTransport.ExpectedRepresentation(
                  uri, 3, new MediaTransport.StrongETag("\"static\""));
          try (var client = transport(defaults)) {
            try {
              client.open(
                  uri,
                  Map.of(),
                  false,
                  new MediaTransport.ByteRange(0L, 2L),
                  new MediaTransport.Cancellation(),
                  expected);
              throw new AssertionError("Typed ICY ranges accepted");
            } catch (MediaTransport.ResourceChangedException failure) {
              check(
                  failure.reason() == MediaTransport.ChangeReason.ENCODING,
                  "typed metadata invalidation reason");
            }
          }
        });
    Path result = directory.resolve("results.json");
    Files.writeString(
        result,
        "{\"jdk\":\"" + System.getProperty("java.version") + "\",\"checks\":" + checks + "}");
    System.out.println("ICY checks passed: " + checks);
  }
}
