package indi.mopelotus.musichud.audio.container.input;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HlsPrefetchedInputTest {
  @Test
  void prefetchedFirstDoesNotRepeatManifestOrSegmentAndRetainsOwner() throws Exception {
    byte[] ts;
    try (var source = getClass().getResourceAsStream("/container/hls/aac.ts")) {
      assertNotNull(source);
      ts = source.readAllBytes();
    }
    byte[] manifest =
        "#EXTM3U\n#EXTINF:1,\nfirst.ts\n#EXT-X-ENDLIST".getBytes(StandardCharsets.UTF_8);
    AtomicInteger opens = new AtomicInteger();
    HlsPlaybackInput.Fetcher fetcher =
        (uri, range, publicOnly) ->
            new HlsPlaybackInput.Request() {
              public HlsPlaybackInput.Resource open() {
                opens.incrementAndGet();
                byte[] data = uri.getPath().endsWith(".m3u8") ? manifest : ts;
                return new HlsPlaybackInput.Resource() {
                  public URI finalUri() {
                    return uri;
                  }

                  public InputStream body() {
                    return new ByteArrayInputStream(data);
                  }

                  public void close() {}
                };
              }

              public void close() {}
            };
    var input =
        new HlsPlaybackInput(URI.create("https://example.com/list.m3u8"), true, null, fetcher);
    var first = input.next();
    assertNotNull(first);
    assertEquals(2, opens.get());
    try (var source = new HlsTsPesSource(input, first)) {
      assertNotNull(source.next());
      assertEquals(2, opens.get());
    }
    assertThrows(IOException.class, input::next);
    assertEquals(2, opens.get());
  }
}
