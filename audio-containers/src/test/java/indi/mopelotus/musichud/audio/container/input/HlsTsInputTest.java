package indi.mopelotus.musichud.audio.container.input;

import indi.mopelotus.musichud.audio.container.hls.HlsManifest;
import indi.mopelotus.musichud.audio.container.ts.TsPacketSource;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class HlsTsInputTest {
  static int checks;

  static void yes(boolean b) {
    checks++;
    if (!b) throw new AssertionError("check " + checks);
  }

  record Pes(byte[] bytes, long pts, boolean discontinuity, int type) {}

  static List<Pes> demux(byte[] stream) throws IOException {
    List<Pes> packets = new ArrayList<>();
    try (var source = new TsPacketSource(new ByteArrayInputStream(stream))) {
      for (var p = source.next(); p != null; p = source.next())
        packets.add(new Pes(p.data(), p.pts90k(), p.discontinuity(), p.streamType()));
    }
    return packets;
  }

  static final class Fetch implements HlsPlaybackInput.Fetcher {
    Map<URI, byte[]> files = new HashMap<>();

    public HlsPlaybackInput.Request request(URI uri, HlsManifest.Range r, boolean pub) {
      return new HlsPlaybackInput.Request() {
        public HlsPlaybackInput.Resource open() throws IOException {
          byte[] b = files.get(uri);
          if (b == null) throw new IOException("missing " + uri);
          return new HlsPlaybackInput.Resource() {
            public URI finalUri() {
              return uri;
            }

            public InputStream body() {
              return new ByteArrayInputStream(b);
            }

            public void close() {}
          };
        }

        public void close() {}
      };
    }
  }

  static HlsTsPesSource source(List<byte[]> segments, Set<Integer> discontinuities)
      throws Exception {
    Fetch fetch = new Fetch();
    StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:7\n");
    for (int i = 0; i < segments.size(); i++) {
      if (discontinuities.contains(i)) playlist.append("#EXT-X-DISCONTINUITY\n");
      playlist.append("#EXTINF:1,\ns").append(i).append('\n');
      fetch.files.put(URI.create("https://example.com/s" + i), segments.get(i));
    }
    playlist.append("#EXT-X-ENDLIST");
    fetch.files.put(
        URI.create("https://example.com/list"),
        playlist.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new HlsTsPesSource(
        new HlsPlaybackInput(URI.create("https://example.com/list"), true, null, fetch));
  }

  static void equal(List<Pes> expected, HlsTsPesSource actual, long epoch) throws Exception {
    for (var e : expected) {
      var p = actual.next();
      yes(p != null);
      yes(Arrays.equals(e.bytes, p.elementary()));
      yes(e.pts == p.pts90k());
      yes(e.discontinuity == p.discontinuity());
      yes(e.type == p.streamType());
      yes(epoch == p.hlsDiscontinuitySequence());
    }
  }

  private static byte[] fixture(String name) throws IOException {
    try (var in = HlsTsInputTest.class.getResourceAsStream("/container/hls/" + name)) {
      if (in == null) throw new IOException("Missing fixture: " + name);
      return in.readAllBytes();
    }
  }

  @Test
  void continuousSegmentsAndDiscontinuityEpochs() throws Exception {
    byte[] ts = fixture("aac.ts");
    List<Pes> one = demux(ts);
    // Deliberately split at non-packet and mid-PES positions: one demux retains its partial
    // reads/state.
    try (var source =
        source(
            List.of(
                Arrays.copyOfRange(ts, 0, 193),
                Arrays.copyOfRange(ts, 193, 997),
                Arrays.copyOfRange(ts, 997, ts.length)),
            Set.of())) {
      equal(one, source, 7);
      yes(source.next() == null);
    }
    // A genuine discontinuity permits a reset of continuity counters and the timestamp epoch.
    try (var source = source(List.of(ts, ts), Set.of(1))) {
      equal(one, source, 7);
      equal(one, source, 8);
      yes(source.next() == null);
    }
    byte[] wrap = fixture("pts-wrap.ts");
    List<Pes> wrapped = demux(wrap);
    yes(wrapped.size() == 2);
    yes(wrapped.getLast().pts > wrapped.getFirst().pts);
    yes(wrapped.getLast().pts >= (1L << 33));
    try (var source =
        source(
            List.of(
                Arrays.copyOfRange(wrap, 0, 3 * 188),
                Arrays.copyOfRange(wrap, 3 * 188, wrap.length)),
            Set.of())) {
      equal(wrapped, source, 7);
      yes(source.next() == null);
    }
    var closed = source(List.of(ts), Set.of());
    closed.close();
    boolean failed = false;
    try {
      closed.next();
    } catch (IOException expectedFailure) {
      failed = true;
    }
    yes(failed);
  }
}
