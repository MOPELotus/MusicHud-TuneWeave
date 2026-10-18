package indi.mopelotus.musichud.audio.container.input;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Ordinary M3U/PLS first-entry resolution. HLS is left intact for HlsManifest. No transport
 * ownership.
 */
public final class PlaylistResolver {
  public enum Kind {
    MEDIA,
    M3U,
    PLS,
    HLS
  }

  public record Loaded(URI finalUri, Kind kind, byte[] body) {
    public Loaded {
      body = body.clone();
    }

    public byte[] body() {
      return body.clone();
    }
  }

  public record Target(URI uri, boolean publicOnly, Kind kind, byte[] playlist) {
    public Target {
      playlist = playlist == null ? null : playlist.clone();
    }

    public byte[] playlist() {
      return playlist == null ? null : playlist.clone();
    }
  }

  @FunctionalInterface
  public interface Loader {
    Loaded load(URI uri, boolean publicOnly) throws IOException;
  }

  private PlaylistResolver() {}

  public static Target resolve(URI root, boolean publicOnly, Loader loader) throws IOException {
    URI current = validate(root, null, publicOnly);
    Set<URI> visited = new HashSet<>();
    for (int depth = 0; depth <= 8; depth++) {
      if (!visited.add(current.normalize())) throw new IOException("Playlist cycle");
      Loaded loaded = Objects.requireNonNull(loader.load(current, publicOnly));
      URI actual = validate(loaded.finalUri(), current, publicOnly);
      if (!actual.equals(current) && !visited.add(actual.normalize()))
        throw new IOException("Redirect playlist cycle");
      if (loaded.kind() == Kind.MEDIA) return new Target(actual, publicOnly, Kind.MEDIA, null);
      if (loaded.kind() == Kind.HLS) return new Target(actual, publicOnly, Kind.HLS, loaded.body());
      String text = decode(loaded.body());
      if (text.lines().anyMatch(s -> s.stripLeading().startsWith("#EXT-X-")))
        return new Target(actual, publicOnly, Kind.HLS, loaded.body());
      if (depth == 8) throw new IOException("Playlist depth limit");
      current = reference(actual, first(text, loaded.kind()), publicOnly);
    }
    throw new AssertionError();
  }

  static String decode(byte[] bytes) throws IOException {
    if (bytes.length > 1048576) throw new IOException("Playlist exceeds 1 MiB");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()
          .replaceFirst("^\\uFEFF", "");
    } catch (CharacterCodingException e) {
      throw new IOException("Invalid playlist UTF-8", e);
    }
  }

  static String first(String text, Kind kind) throws IOException {
    String[] lines = text.split("\\r?\\n", -1);
    if (lines.length > 20000) throw new IOException("Too many playlist lines");
    if (kind == Kind.M3U) {
      for (String line : lines) {
        if (line.length() > 16384) throw new IOException("Playlist line too long");
        line = line.strip();
        if (!line.isEmpty() && !line.startsWith("#")) return line;
      }
    } else if (kind == Kind.PLS) {
      TreeMap<Integer, String> files = new TreeMap<>();
      boolean playlist = false;
      for (String raw : lines) {
        if (raw.length() > 16384) throw new IOException("Playlist line too long");
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
        if (line.startsWith("[")) {
          playlist = line.equalsIgnoreCase("[playlist]");
          continue;
        }
        if (!playlist) continue;
        int eq = line.indexOf('=');
        if (eq < 1) continue;
        String key = line.substring(0, eq).strip(), v = line.substring(eq + 1).strip();
        if (key.matches("(?i)File[0-9]+")) {
          try {
            int id = Integer.parseInt(key.substring(4));
            if (id < 1 || files.putIfAbsent(id, v) != null)
              throw new IOException("Duplicate/invalid PLS entry");
          } catch (NumberFormatException e) {
            throw new IOException("Invalid PLS index", e);
          }
        }
      }
      if (!files.isEmpty() && !files.firstEntry().getValue().isEmpty())
        return files.firstEntry().getValue();
    }
    throw new IOException("Playlist has no media entry");
  }

  static URI reference(URI base, String value, boolean publicOnly) throws IOException {
    if (value.length() > 16384
        || value.chars().anyMatch(c -> c < 32 || c == 127)
        || value.indexOf('\\') >= 0) throw new IOException("Invalid playlist reference");
    try {
      return validate(base.resolve(new URI(value)), base, publicOnly);
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw new IOException("Invalid playlist URI", e);
    }
  }

  public static URI validate(URI uri, URI parent, boolean publicOnly) throws IOException {
    if (uri == null
        || !uri.isAbsolute()
        || uri.isOpaque()
        || uri.getUserInfo() != null
        || uri.getFragment() != null) throw new IOException("Invalid media URI");
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (scheme.equals("icy")) {
      try {
        uri =
            new URI(
                "http", null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), null);
        scheme = "http";
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
    }
    if (scheme.equals("file")) {
      if (publicOnly
          || parent != null && !parent.getScheme().equalsIgnoreCase("file")
          || uri.getAuthority() != null && !uri.getAuthority().isEmpty()
          || uri.getQuery() != null) throw new IOException("Remote/local playlist boundary");
      try {
        Path.of(uri);
      } catch (RuntimeException e) {
        throw new IOException("Invalid local URI", e);
      }
      return uri.normalize();
    }
    if (!scheme.equals("http") && !scheme.equals("https")
        || uri.getHost() == null
        || uri.getPort() == 0
        || uri.getPort() > 65535) throw new IOException("Unsupported media URI");
    // publicOnly is deliberately sticky across local/remote links. Transport must enforce DNS and
    // redirects.
    return uri.normalize();
  }
}
