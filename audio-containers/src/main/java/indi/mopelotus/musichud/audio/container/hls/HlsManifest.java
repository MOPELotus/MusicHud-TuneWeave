package indi.mopelotus.musichud.audio.container.hls;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded manifest model, independent of HTTP and decoding. AES primitive is JDK Cipher. */
public final class HlsManifest {
  public record Range(long offset, long length) {}

  public record Key(URI uri, byte[] explicitIv) {
    public Key {
      explicitIv = explicitIv == null ? null : explicitIv.clone();
    }

    public byte[] explicitIv() {
      return explicitIv == null ? null : explicitIv.clone();
    }

    byte[] iv(long sequence) {
      if (explicitIv != null) return explicitIv.clone();
      return ByteBuffer.allocate(16).putLong(0).putLong(sequence).array();
    }
  }

  public record Init(URI uri, Range range, Key key) {}

  public record Segment(
      URI uri,
      long sequence,
      double durationSeconds,
      Range range,
      Key key,
      Init init,
      boolean discontinuity) {}

  public record Variant(URI uri, long bandwidth, String audioGroup) {
    public Variant(URI uri, long bandwidth) {
      this(uri, bandwidth, null);
    }
  }

  public record Audio(
      String group,
      String name,
      String language,
      boolean defaultSelection,
      boolean autoselect,
      URI uri) {}

  public record Manifest(
      List<Segment> segments,
      List<Variant> variants,
      boolean endList,
      long discontinuitySequence,
      List<Audio> audio,
      double targetDuration) {}

  public static Manifest parse(URI base, String text) throws IOException {
    if (text.length() > 1048576) throw new IOException("Manifest exceeds 1 MiB");
    String[] lines = text.replace("\r", "").split("\n", -1);
    if (lines.length > 20000) throw new IOException("Too many lines");
    if (lines.length == 0 || !lines[0].replace("\ufeff", "").equals("#EXTM3U"))
      throw new IOException("Not extended M3U");
    List<Segment> segments = new ArrayList<>();
    List<Variant> variants = new ArrayList<>();
    List<Audio> audio = new ArrayList<>();
    String audioGroup = null;
    double targetDuration = 0;
    Key key = null;
    Init init = null;
    long seq = 0, discSeq = 0;
    Double duration = null;
    String pendingRange = null;
    Long bandwidth = null;
    boolean discontinuity = false, end = false;
    URI previousUri = null;
    long previousEnd = -1;
    for (int i = 1; i < lines.length; i++) {
      String l = lines[i].strip();
      if (l.isEmpty()) continue;
      if (l.length() > 16384) throw new IOException("Line exceeds bound");
      if (l.startsWith("#EXT-X-MEDIA:")) {
        var a = attrs(l.substring(13));
        if (required(a, "TYPE").equals("AUDIO")) {
          String g = required(a, "GROUP-ID"), name = required(a, "NAME");
          boolean d = yesNo(a, "DEFAULT"), auto = yesNo(a, "AUTOSELECT");
          if (d && !auto) throw new IOException("DEFAULT audio must AUTOSELECT");
          for (Audio old : audio)
            if (old.group().equals(g) && old.name().equals(name))
              throw new IOException("Duplicate audio rendition");
          audio.add(
              new Audio(
                  g,
                  name,
                  a.get("LANGUAGE"),
                  d,
                  auto,
                  a.containsKey("URI") ? resolve(base, a.get("URI")) : null));
        }
      } else if (l.startsWith("#EXT-X-TARGETDURATION:")) {
        long n = nonnegative(l.substring(22));
        if (n == 0 || n > 86400) throw new IOException("Invalid target duration");
        targetDuration = n;
      } else if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
        if (!segments.isEmpty()) throw new IOException("Late media sequence");
        seq = nonnegative(l.substring(22));
      } else if (l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
        if (!segments.isEmpty()) throw new IOException("Late discontinuity sequence");
        discSeq = nonnegative(l.substring(30));
      } else if (l.startsWith("#EXTINF:")) {
        if (duration != null) throw new IOException("Missing segment URI");
        String value = l.substring(8).split(",", 2)[0];
        try {
          duration = Double.parseDouble(value);
        } catch (NumberFormatException e) {
          throw new IOException("Invalid duration", e);
        }
        if (!Double.isFinite(duration) || duration < 0) throw new IOException("Invalid duration");
      } else if (l.startsWith("#EXT-X-KEY:")) {
        Map<String, String> a = attrs(l.substring(11));
        String method = required(a, "METHOD");
        if (method.equals("NONE")) {
          key = null;
          continue;
        }
        if (!method.equals("AES-128")
            || !a.getOrDefault("KEYFORMAT", "identity").equals("identity"))
          throw new IOException("Unsupported HLS encryption " + method);
        byte[] iv = null;
        if (a.containsKey("IV")) {
          String s = a.get("IV");
          if (!s.matches("0[xX][0-9a-fA-F]{1,32}")) throw new IOException("Invalid AES IV");
          s = s.substring(2);
          s = "0".repeat(32 - s.length()) + s;
          iv = HexFormat.of().parseHex(s);
        }
        key = new Key(resolve(base, required(a, "URI")), iv);
      } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
        pendingRange = l.substring("#EXT-X-BYTERANGE:".length());
      } else if (l.startsWith("#EXT-X-MAP:")) {
        var a = attrs(l.substring(11));
        URI uri = resolve(base, required(a, "URI"));
        Range r = a.containsKey("BYTERANGE") ? range(a.get("BYTERANGE"), -1) : null;
        if (key != null && key.explicitIv() == null)
          throw new IOException("Encrypted init requires explicit IV");
        init = new Init(uri, r, key);
      } else if (l.startsWith("#EXT-X-STREAM-INF:")) {
        var a = attrs(l.substring(18));
        bandwidth = nonnegative(required(a, "BANDWIDTH"));
        audioGroup = a.get("AUDIO");
      } else if (l.equals("#EXT-X-DISCONTINUITY")) {
        discontinuity = true;
      } else if (l.equals("#EXT-X-ENDLIST")) {
        end = true;
      } else if (!l.startsWith("#")) {
        URI uri = resolve(base, l);
        if (bandwidth != null) {
          variants.add(new Variant(uri, bandwidth, audioGroup));
          bandwidth = null;
          continue;
        }
        if (duration == null) throw new IOException("Segment missing duration");
        Range r =
            pendingRange == null
                ? null
                : range(pendingRange, uri.equals(previousUri) ? previousEnd : -1);
        segments.add(new Segment(uri, seq, duration, r, key, init, discontinuity));
        try {
          seq = Math.addExact(seq, 1);
        } catch (ArithmeticException e) {
          throw new IOException("Sequence overflow", e);
        }
        previousUri = uri;
        previousEnd = r == null ? -1 : Math.addExact(r.offset, r.length);
        duration = null;
        pendingRange = null;
        discontinuity = false;
      }
    }
    if (duration != null || bandwidth != null || pendingRange != null)
      throw new IOException("Truncated manifest");
    if (!segments.isEmpty() && !variants.isEmpty())
      throw new IOException("Mixed master/media manifest");
    return new Manifest(
        List.copyOf(segments),
        List.copyOf(variants),
        end,
        discSeq,
        List.copyOf(audio),
        targetDuration);
  }

  private static boolean yesNo(Map<String, String> a, String k) throws IOException {
    String v = a.getOrDefault(k, "NO");
    if (!v.equals("YES") && !v.equals("NO")) throw new IOException("Invalid " + k);
    return v.equals("YES");
  }

  private static URI resolve(URI base, String s) throws IOException {
    try {
      URI u = base.resolve(new URI(s));
      if (u.getFragment() != null) throw new IOException("Fragment in HLS URI");
      return u;
    } catch (IllegalArgumentException | URISyntaxException e) {
      throw new IOException("Invalid HLS URI", e);
    }
  }

  private static String required(Map<String, String> a, String k) throws IOException {
    String v = a.get(k);
    if (v == null || v.isEmpty()) throw new IOException("Missing " + k);
    return v;
  }

  private static long nonnegative(String s) throws IOException {
    try {
      long n = Long.parseLong(s);
      if (n < 0) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IOException("Invalid nonnegative integer", e);
    }
  }

  private static Range range(String s, long inferred) throws IOException {
    String[] parts = s.split("@", -1);
    if (parts.length > 2) throw new IOException("Invalid byte range");
    long n = nonnegative(parts[0]), o = parts.length == 2 ? nonnegative(parts[1]) : inferred;
    if (n == 0 || o < 0) throw new IOException("Invalid implicit byte range");
    try {
      Math.addExact(o, n);
    } catch (ArithmeticException e) {
      throw new IOException("Range overflow", e);
    }
    return new Range(o, n);
  }

  static Map<String, String> attrs(String s) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    int i = 0;
    while (i < s.length()) {
      int eq = s.indexOf('=', i);
      if (eq <= i) throw new IOException("Invalid attribute");
      String k = s.substring(i, eq);
      i = eq + 1;
      String value;
      if (i < s.length() && s.charAt(i) == '"') {
        int end = s.indexOf('"', i + 1);
        if (end < 0) throw new IOException("Unclosed quote");
        value = s.substring(i + 1, end);
        i = end + 1;
      } else {
        int end = s.indexOf(',', i);
        if (end < 0) end = s.length();
        value = s.substring(i, end);
        i = end;
      }
      if (out.putIfAbsent(k, value) != null) throw new IOException("Duplicate attribute " + k);
      if (i < s.length()) {
        if (s.charAt(i) != ',') throw new IOException("Invalid separator");
        i++;
        if (i == s.length()) throw new IOException("Trailing separator");
      }
    }
    return out;
  }
}
