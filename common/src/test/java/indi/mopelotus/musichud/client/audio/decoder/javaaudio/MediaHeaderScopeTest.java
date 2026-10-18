package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaHeaderScopeTest {
  @Test
  void credentialsAndUnknownPrivateHeadersStayAtOriginalOrigin() {
    var headers =
        Map.of(
            "Cookie",
            "private",
            "Authorization",
            "secret",
            "X-Token",
            "token",
            "User-Agent",
            "audio",
            "Referer",
            "https://platform.invalid/");
    var scope = new MediaHeaderScope(URI.create("https://origin.invalid/list"), headers);
    assertEquals(headers, scope.forUri(URI.create("https://ORIGIN.invalid:443/segment")));
    var publicHeaders = Map.of("User-Agent", "audio", "Referer", "https://platform.invalid/");
    assertEquals(publicHeaders, scope.forUri(URI.create("https://cdn.invalid/song")));
    assertEquals(publicHeaders, scope.forUri(URI.create("http://origin.invalid/song")));
    assertEquals(publicHeaders, scope.forUri(URI.create("file:///song")));
  }
}
