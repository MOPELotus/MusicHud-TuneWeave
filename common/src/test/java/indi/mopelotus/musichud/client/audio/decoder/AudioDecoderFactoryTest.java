package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import indi.mopelotus.musichud.beans.music.FormatType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AudioDecoderFactoryTest {
  @Test
  void failedSignedResourcesDoNotEmbedTheirQueryInDecoderDiagnostics() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    try {
      String secret = "private-signature-audio-query";
      String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing?token=" + secret;
      Throwable failure =
          assertThrows(
              RuntimeException.class, () -> AudioDecoderFactory.open(uri, FormatType.AUTO));
      var trace = new java.io.StringWriter();
      failure.printStackTrace(new java.io.PrintWriter(trace));
      assertFalse(trace.toString().contains(secret));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void probeReportsOpenedBytesWithOneHttpRequest() throws Exception {
    byte[] wav = fixture();
    AtomicInteger requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/unknown.mp3",
        exchange -> {
          requests.incrementAndGet();
          try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, wav.length);
            exchange.getResponseBody().write(wav);
          }
        });
    server.start();
    try {
      String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/unknown.mp3";
      var probe = AudioDecoderFactory.probe(uri, FormatType.MP3);
      assertEquals(1, requests.get(), "Metadata must reuse the decoded response");
      assertEquals(uri, probe.identifier());
      assertEquals(FormatType.MP3, probe.declaredFormat());
      assertEquals(FormatType.WAV, probe.detectedFormat());
      assertFalse(probe.backend().isBlank());
      assertFalse(probe.backend().toLowerCase(java.util.Locale.ROOT).contains("lava"));
      assertTrue(probe.channelCount() > 0);
      assertTrue(probe.sampleRate() > 0);
      assertTrue(probe.probeBytesRead() > 0);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void allTrustedOpenOverloadsRetainLocalAndDeviceOptions() throws Exception {
    Path file = Files.createTempFile("java-default-", ".bin");
    try {
      Files.write(file, fixture());
      try (var decoder = AudioDecoderFactory.open(file.toString(), FormatType.AUTO)) {
        assertNotNull(decoder.readChunk(4096));
      }
      try (var decoder =
          AudioDecoderFactory.open(file.toUri().toString(), FormatType.GENERIC, null)) {
        assertNotNull(decoder.readChunk(4096));
      }
      try (var decoder =
          AudioDecoderFactory.open(file.toString(), FormatType.AUTO, Map.of(), true)) {
        assertNotNull(decoder.readChunk(4096));
      }
      try (var decoder =
          AudioDecoderFactory.open(file.toString(), FormatType.AUTO, Map.of(), false, true)) {
        assertEquals(AudioDecoder.SampleEncoding.PCM_S16_LE, decoder.getSampleEncoding());
        assertNotNull(decoder.readChunk(4096));
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void publicOverloadsRejectLocalAndPrivateHttpBeforeReading() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AudioDecoderFactory.openPublicResource(
                "file:///tmp/local.wav", FormatType.AUTO, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AudioDecoderFactory.openPublicResource(
                "http://127.0.0.1:1/a", FormatType.AUTO, Map.of(), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AudioDecoderFactory.openPublicResource(
                "http://localhost:1/a", FormatType.AUTO, Map.of(), false, true));
    assertThrows(
        IllegalArgumentException.class, () -> AudioDecoderFactory.open(" ", FormatType.AUTO));
  }

  private static byte[] fixture() throws IOException {
    try (var input = AudioDecoderFactoryTest.class.getResourceAsStream("/javaaudio/tone.wav")) {
      if (input == null) throw new IOException("Missing synthetic WAV fixture");
      return input.readAllBytes();
    }
  }
}
