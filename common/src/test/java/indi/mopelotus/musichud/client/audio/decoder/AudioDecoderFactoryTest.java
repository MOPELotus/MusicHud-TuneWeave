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
  void malformedResourceUrisFailWithoutLeakingQueriesThroughAnyCause() throws Exception {
    String secret = "private-signed-malformed-query";
    for (String uri :
        new String[] {
          "https://example.invalid/audio?token=" + secret + "%zz",
          "HTTP://example.invalid/audio?token=" + secret + "%",
          "icy://example.invalid/audio?token=" + secret + "%zz",
          "https:/media?token=" + secret,
          "http:media?token=" + secret,
          "file:///tmp/audio?token=" + secret,
          "file:///tmp/" + secret + "%zz",
          "file:relative?token=" + secret,
          "https://user:" + secret + "@example.invalid/audio"
        }) {
      for (int entry = 0; entry < 3; entry++) {
        final int selected = entry;
        Throwable error =
            assertThrows(
                Exception.class,
                () -> {
                  if (selected == 0) AudioFormatDetector.normalizeIdentifier(uri);
                  else if (selected == 1) AudioDecoderFactory.open(uri, FormatType.AUTO);
                  else AudioDecoderFactory.probe(uri, FormatType.AUTO);
                });
        var trace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(trace));
        assertFalse(
            trace.toString().contains(secret),
            "Malformed URI must not become a local filename or retained parser cause");
      }
    }
  }

  @Test
  void literalLocalPathsKeepSpacesPercentSignsQueriesAndDriveLetters() throws Exception {
    Path directory = Files.createTempDirectory("java-audio-paths-");
    Path file = directory.resolve("local audio?literal=%zz.wav");
    try {
      Files.write(file, fixture());
      assertEquals(
          file.toAbsolutePath().normalize().toString(),
          AudioFormatDetector.normalizeIdentifier(file.toString()));
      assertEquals(
          file.toAbsolutePath().normalize().toString(),
          AudioFormatDetector.normalizeIdentifier(file.toUri().toString()));
      try (var decoder = AudioDecoderFactory.open(file.toString(), FormatType.AUTO)) {
        assertNotNull(decoder.readChunk(4096));
      }
      try (var decoder = AudioDecoderFactory.open(file.toUri().toString(), FormatType.AUTO)) {
        assertNotNull(decoder.readChunk(4096));
      }
      String relative = "missing local audio?literal=%zz.wav";
      assertEquals(
          Path.of(relative).toAbsolutePath().normalize().toString(),
          AudioFormatDetector.normalizeIdentifier(relative));
      for (String drive :
          new String[] {"C:\\audio files\\literal%zz.wav", "D:/audio files/literal%zz.wav"})
        assertEquals(
            Path.of(drive).toAbsolutePath().normalize().toString(),
            AudioFormatDetector.normalizeIdentifier(drive));
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void probeRetainsTheValidatedLogicalOggCodec() throws Exception {
    Path file = Files.createTempFile("java-ogg-codec-", ".bin");
    try {
      for (String name : new String[] {"tone.opus", "tone-second.ogg"}) {
        try (var input = getClass().getResourceAsStream("/javaaudio/" + name)) {
          Files.write(file, input.readAllBytes());
        }
        assertEquals(
            name.endsWith(".opus") ? FormatType.OPUS : FormatType.OGG,
            AudioDecoderFactory.probe(file.toString(), FormatType.AUTO).detectedFormat());
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

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
