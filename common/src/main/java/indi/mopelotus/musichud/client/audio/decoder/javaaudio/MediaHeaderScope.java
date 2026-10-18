package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import java.net.URI;
import java.util.*;

/** Playlist and HLS references may cross origins; credentials stay with their supplied origin. */
public final class MediaHeaderScope {
  private static final Set<String> PUBLIC =
      Set.of("accept", "accept-language", "origin", "referer", "user-agent");
  private final URI origin;
  private final Map<String, String> headers;

  public MediaHeaderScope(URI origin, Map<String, String> headers) {
    this.origin = Objects.requireNonNull(origin);
    this.headers = Map.copyOf(headers);
  }

  public Map<String, String> forUri(URI target) {
    if (sameOrigin(origin, target)) return headers;
    Map<String, String> selected = new LinkedHashMap<>();
    headers.forEach(
        (name, value) -> {
          if (PUBLIC.contains(name.toLowerCase(Locale.ROOT))) selected.put(name, value);
        });
    return Map.copyOf(selected);
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getHost() != null
        && b.getHost() != null
        && a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }
}
