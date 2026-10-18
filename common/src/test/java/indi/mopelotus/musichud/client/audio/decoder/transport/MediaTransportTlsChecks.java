package indi.mopelotus.musichud.client.audio.decoder.transport;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

final class MediaTransportTlsChecks {
  public static void main(String[] args) throws Exception {
    char[] password = "test-only-password".toCharArray();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      keys.load(in, password);
    }
    KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, password);
    TrustManagerFactory tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(keys);
    SSLContext serverTls = SSLContext.getInstance("TLS");
    serverTls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    SSLContext clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, tm.getTrustManagers(), null);
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
    ExecutorService pool = Executors.newCachedThreadPool();
    server.setExecutor(pool);
    AtomicReference<List<SNIServerName>> sni = new AtomicReference<>();
    AtomicInteger hits = new AtomicInteger();
    server.createContext(
        "/ok",
        e -> {
          hits.incrementAndGet();
          sni.set(
              ((ExtendedSSLSession) ((HttpsExchange) e).getSSLSession()).getRequestedServerNames());
          e.sendResponseHeaders(200, 2);
          e.getResponseBody().write(new byte[] {79, 75});
          e.close();
        });
    server.start();
    MediaTransport.Resolver resolver =
        host -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})};
    String suffix = ":" + server.getAddress().getPort() + "/ok";
    try (MediaTransport client = new MediaTransport(resolver, clientTls, 2000)) {
      try (var r =
          client.open(
              URI.create("https://media.test" + suffix),
              Map.of(),
              false,
              null,
              new MediaTransport.Cancellation())) {
        if (!new String(r.body().readAllBytes()).equals("OK")) throw new AssertionError("TLS body");
      }
      System.out.println("PASS TLS pinned IP with original hostname certificate verified");
      if (sni.get() == null
          || sni.get().size() != 1
          || !((SNIHostName) sni.get().getFirst()).getAsciiName().equals("media.test"))
        throw new AssertionError("SNI missing");
      System.out.println("PASS TLS original hostname SNI");
      int before = hits.get();
      try {
        client.open(
            URI.create("https://wrong.test" + suffix),
            Map.of(),
            false,
            null,
            new MediaTransport.Cancellation());
        throw new AssertionError("Wrong hostname accepted");
      } catch (IOException expected) {
      }
      if (hits.get() != before) throw new AssertionError("Wrong hostname sent HTTP");
      System.out.println("PASS TLS wrong hostname rejected before HTTP request");
    }
    try (MediaTransport untrusted = new MediaTransport(resolver, SSLContext.getDefault(), 2000)) {
      int before = hits.get();
      try {
        untrusted.open(
            URI.create("https://media.test" + suffix),
            Map.of(),
            false,
            null,
            new MediaTransport.Cancellation());
        throw new AssertionError("Untrusted cert accepted");
      } catch (IOException expected) {
      }
      if (hits.get() != before) throw new AssertionError("Untrusted cert sent HTTP");
      System.out.println("PASS TLS untrusted certificate rejected before HTTP request");
    } finally {
      server.stop(0);
      pool.shutdownNow();
    }
    Files.writeString(
        Path.of(args[1]), "{\"java\":\"" + System.getProperty("java.version") + "\",\"passed\":4}");
  }
}
