package indi.mopelotus.musichud.audio.container.hls;

import java.io.*;
import java.net.*;
import java.util.*;

/** Reload bookkeeping and explicit ownership/cancellation, independent of transport. */
public final class HlsSession implements AutoCloseable {
  private long next = -1, window = -1, generation;
  private boolean closed, ended;
  private AutoCloseable active;
  private final LinkedHashMap<Long, String> seen = new LinkedHashMap<>();

  public synchronized long generation() throws IOException {
    check();
    return generation;
  }

  public void attach(long token, AutoCloseable resource) throws IOException {
    Objects.requireNonNull(resource);
    String error = null;
    synchronized (this) {
      if (closed || token != generation) error = "Stale HLS request";
      else if (active != null) error = "HLS resource still active";
      else active = resource;
    }
    if (error != null) {
      quiet(resource);
      throw new IOException(error);
    }
  }

  public synchronized void detach(AutoCloseable resource) {
    if (active == resource) active = null;
  }

  public synchronized List<HlsManifest.Segment> reload(long token, HlsManifest.Manifest m)
      throws IOException {
    check();
    if (token != generation) throw new IOException("Stale HLS reload");
    if (!m.variants().isEmpty()) throw new IOException("Expected media playlist");
    if (ended) throw new IOException("Reload after ENDLIST");
    var segments = m.segments();
    if (segments.isEmpty()) {
      if (m.endList()) ended = true;
      return List.of();
    }
    long first = segments.getFirst().sequence();
    if (first < window) throw new IOException("HLS window regressed");
    if (next >= 0 && first > next) throw new IOException("HLS live window skipped unread segment");
    List<HlsManifest.Segment> fresh = new ArrayList<>();
    for (var s : segments) {
      String fingerprint =
          s.uri()
              + "|"
              + s.range()
              + "|"
              + s.durationSeconds()
              + "|"
              + s.discontinuity()
              + "|"
              + key(s.key())
              + "|"
              + init(s.init());
      String old = seen.get(s.sequence());
      if (old != null && !old.equals(fingerprint))
        throw new IOException("HLS segment identity changed");
      if (next < 0 || s.sequence() >= next) fresh.add(s);
    }
    // Commit only after complete validation. Caller processes returned segments in order before
    // reload.
    for (var s : segments)
      seen.put(
          s.sequence(),
          s.uri()
              + "|"
              + s.range()
              + "|"
              + s.durationSeconds()
              + "|"
              + s.discontinuity()
              + "|"
              + key(s.key())
              + "|"
              + init(s.init()));
    while (seen.size() > 20000) seen.remove(seen.keySet().iterator().next());
    window = first;
    if (!fresh.isEmpty()) {
      try {
        next = Math.addExact(fresh.getLast().sequence(), 1);
      } catch (ArithmeticException e) {
        throw new IOException("Sequence overflow", e);
      }
    }
    ended = m.endList();
    return List.copyOf(fresh);
  }

  private static String key(HlsManifest.Key k) {
    return k == null
        ? "none"
        : k.uri()
            + ":"
            + (k.explicitIv() == null ? "sequence" : HexFormat.of().formatHex(k.explicitIv()));
  }

  private static String init(HlsManifest.Init i) {
    return i == null ? "none" : i.uri() + ":" + i.range() + ":" + key(i.key());
  }

  public synchronized long reloadDelayMillis(HlsManifest.Manifest m, boolean changed)
      throws IOException {
    check();
    if (m.endList()) return -1;
    if (m.targetDuration() <= 0) throw new IOException("Live HLS requires target duration");
    return Math.max(250, (long) (m.targetDuration() * 1000 * (changed ? 1 : .5)));
  }

  public synchronized HlsManifest.Audio selectAudio(
      HlsManifest.Manifest m, String group, String language) throws IOException {
    check();
    var candidates = m.audio().stream().filter(a -> a.group().equals(group)).toList();
    if (candidates.isEmpty()) throw new IOException("Missing audio group");
    if (language != null)
      for (var a : candidates) if (language.equalsIgnoreCase(a.language())) return a;
    for (var a : candidates) if (a.defaultSelection()) return a;
    for (var a : candidates) if (a.autoselect()) return a;
    return candidates.getFirst();
  }

  public void reset() throws IOException {
    AutoCloseable old;
    synchronized (this) {
      check();
      generation++;
      old = active;
      active = null;
      next = -1;
      window = -1;
      ended = false;
      seen.clear();
    }
    quiet(old);
  }

  private void check() throws IOException {
    if (closed) throw new IOException("HLS cancelled");
  }

  private static void quiet(AutoCloseable c) {
    if (c != null)
      try {
        c.close();
      } catch (Exception ignored) {
      }
  }

  public void close() {
    AutoCloseable old;
    synchronized (this) {
      if (closed) return;
      closed = true;
      generation++;
      old = active;
      active = null;
      seen.clear();
    }
    quiet(old);
  }
}
