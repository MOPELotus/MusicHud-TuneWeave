package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcmAudioInputTest {
  @TempDir Path temporary;

  @Test
  void localFilesRemainAvailableOnlyInPrivateMode() throws Exception {
    Path audio = temporary.resolve("audio.wav");
    byte[] bytes = {1, 2, 3, 4};
    Files.write(audio, bytes);
    try (InputStream input = PcmAudioInput.open(audio.toString(), Map.of(), false)) {
      assertArrayEquals(bytes, input.readAllBytes());
    }
    assertThrows(
        IOException.class, () -> PcmAudioInput.openPublicMedia(audio.toString(), Map.of()));
  }

  @Test
  void privateHttpPreservesHeadersAndPublicArtworkRejectsLocalNetwork() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/audio",
        exchange -> {
          byte[] body =
              String.valueOf(exchange.getRequestHeaders().getFirst("X-Media-Test"))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    String uri = "HTTP://127.0.0.1:" + server.getAddress().getPort() + "/audio";
    try {
      try (InputStream stream =
          PcmAudioInput.open(uri, Map.of("X-Media-Test", "preserved"), false)) {
        assertEquals(
            "preserved",
            new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      assertThrows(IOException.class, () -> PcmAudioInput.openPublicMedia(uri, Map.of()));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void invalidUrlsFailWithoutEchoingCredentialMaterial() {
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                PcmAudioInput.open(
                    "https://host.test/bad path?token=DO_NOT_ECHO", Map.of(), false));
    assertFalse(failure.toString().contains("DO_NOT_ECHO"));
  }
}
