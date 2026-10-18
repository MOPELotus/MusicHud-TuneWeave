package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mp4FragmentMetadataTest {
  @TempDir Path temporary;

  static byte[] fixture(String name) throws IOException {
    try (var input = Mp4FragmentMetadataTest.class.getResourceAsStream("/container/fmp4/" + name)) {
      if (input == null) throw new IOException("Missing fixture " + name);
      return input.readAllBytes();
    }
  }

  static byte[] joined(byte[] init) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(init);
    out.write(fixture("media0.m4s"));
    out.write(fixture("media1.m4s"));
    return out.toByteArray();
  }

  Path file(String name, byte[] bytes) throws IOException {
    return Files.write(temporary.resolve(name), bytes);
  }

  @Test
  void fragmentsHaveExactSampleEndpointAndPreserveTheRawOpenEndedEdit() throws Exception {
    try (var source = new Mp4PacketSource(file("joined.mp4", joined(fixture("init.mp4"))))) {
      assertTrue(source.hasOpenEndedFinalEdit());
      assertEquals(new PacketSource.Time(63424, 48000), source.track().mediaDuration());
      assertEquals(1, source.track().edits().size());
      var edit = source.track().edits().getFirst();
      assertEquals(0, edit.presentationDuration().ticks());
      assertEquals(0, edit.mediaStart().ticks());
      int count = 0;
      PacketSource.Packet packet, last = null;
      while ((packet = source.next()) != null) {
        assertFalse(packet.discontinuity());
        last = packet;
        count++;
      }
      assertEquals(62, count);
      assertNotNull(last);
      assertEquals(new PacketSource.Time(62464, 48000), last.decodeTime());
      assertEquals(new PacketSource.Time(960, 48000), last.duration());
    }
  }

  @Test
  void initializationUnknownDurationDiffersFromAnActuallyEmptyClassicTrack() throws Exception {
    byte[] init = fixture("init.mp4");
    try (var source = new Mp4PacketSource(file("init.mp4", init))) {
      assertNull(source.track().mediaDuration());
      assertTrue(source.hasOpenEndedFinalEdit());
      assertNull(source.next());
    }
    byte[] classic = init.clone();
    System.arraycopy(
        "free".getBytes(StandardCharsets.US_ASCII), 0, classic, find(classic, "mvex"), 4);
    try (var source = new Mp4PacketSource(file("classic.mp4", classic))) {
      assertEquals(new PacketSource.Time(0, 48000), source.track().mediaDuration());
      assertFalse(source.hasOpenEndedFinalEdit());
      assertEquals(0, source.track().edits().getFirst().presentationDuration().ticks());
      assertNull(source.next());
    }
    byte[] otherTrack = init.clone();
    ByteBuffer.wrap(otherTrack).putInt(find(otherTrack, "trex") + 8, 2);
    try (var source = new Mp4PacketSource(file("other-track.mp4", otherTrack))) {
      assertFalse(source.hasOpenEndedFinalEdit());
      assertEquals(new PacketSource.Time(0, 48000), source.track().mediaDuration());
    }
  }

  @Test
  void markerPreservesPrimingRateAndFiniteOrTerminalEmptyEditSemantics() throws Exception {
    byte[] init = fixture("init.mp4");
    int edit = find(init, "elst");
    ByteBuffer.wrap(init).putInt(edit + 16, 1024).putInt(edit + 20, 2 << 16);
    try (var source = new Mp4PacketSource(file("primed.mp4", joined(init)))) {
      assertTrue(source.hasOpenEndedFinalEdit());
      assertEquals(1024, source.track().edits().getFirst().mediaStart().ticks());
      assertEquals(
          2 * source.track().edits().getFirst().rate().denominator(),
          source.track().edits().getFirst().rate().numerator());
      assertEquals(new PacketSource.Time(63424, 48000), source.track().mediaDuration());
    }
    ByteBuffer.wrap(init).putInt(edit + 12, 1300);
    try (var source = new Mp4PacketSource(file("finite.mp4", joined(init)))) {
      assertFalse(source.hasOpenEndedFinalEdit());
      assertEquals(1300, source.track().edits().getFirst().presentationDuration().ticks());
    }
    ByteBuffer.wrap(init).putInt(edit + 12, 0).putInt(edit + 16, -1);
    try (var source = new Mp4PacketSource(file("empty-edit.mp4", joined(init)))) {
      assertFalse(source.hasOpenEndedFinalEdit());
      assertNull(source.track().edits().getFirst().mediaStart());
    }
    byte[] twoEdits = appendEmptyEdit(fixture("init.mp4"));
    try (var source = new Mp4PacketSource(file("terminal-empty.mp4", joined(twoEdits)))) {
      assertFalse(source.hasOpenEndedFinalEdit());
      assertEquals(2, source.track().edits().size());
      assertNotNull(source.track().edits().getFirst().mediaStart());
      assertNull(source.track().edits().getLast().mediaStart());
    }
  }

  @Test
  void realShadedArtifactsPreserveMetadataAndPcmInBothAssertionModes() throws Exception {
    byte[] expected;
    try (var zipped = new GZIPInputStream(new ByteArrayInputStream(fixture("ffmpeg.s16le.gz")))) {
      expected = zipped.readAllBytes();
    }
    assertEquals(63488 * 4, expected.length); // Codec frames exceed declared sample end by 64.
    byte[] previous = null;
    for (boolean assertions : List.of(false, true)) {
      for (int mediaStart : List.of(0, 1024)) {
        byte[] init = fixture("init.mp4");
        ByteBuffer.wrap(init).putInt(find(init, "elst") + 16, mediaStart);
        Path input = file("standalone-" + mediaStart + ".mp4", joined(init));
        Path pcm = temporary.resolve("decoded-" + assertions + "-" + mediaStart + ".pcm");
        Path log = temporary.resolve("decode-" + assertions + "-" + mediaStart + ".log");
        String classes =
            Path.of(
                    ShadedFragmentMetadataProbe.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var process =
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    assertions ? "-ea" : "-da",
                    "-cp",
                    classes,
                    ShadedFragmentMetadataProbe.class.getName(),
                    System.getProperty("containerJar"),
                    System.getProperty("packetCodecJar"),
                    System.getProperty("packetHostSlf4jJar"),
                    input.toString(),
                    pcm.toString(),
                    Boolean.toString(assertions),
                    Integer.toString(mediaStart))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          fail("Fragment decoding timed out");
        }
        assertEquals(0, process.exitValue(), () -> read(log));
        byte[] actual = Files.readAllBytes(pcm);
        assertEquals(expected.length, actual.length);
        if (previous != null) assertArrayEquals(previous, actual);
        previous = actual;
        // Presentation mapper must trim the final codec padding and apply any priming once.
        // Compare both zero-start and nonzero-start windows against the independent PCM oracle.
        int start = mediaStart;
        var a =
            ByteBuffer.wrap(actual, start * 4, (63424 - start) * 4).order(ByteOrder.LITTLE_ENDIAN);
        var b =
            ByteBuffer.wrap(expected, start * 4, (63424 - start) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        double error = 0, signal = 0;
        while (a.hasRemaining()) {
          int x = a.getShort(), y = b.getShort();
          error += (double) (x - y) * (x - y);
          signal += (double) y * y;
        }
        double snr = 10 * Math.log10(signal / Math.max(1, error));
        assertTrue(snr > 45, "Fragment AAC PCM SNR=" + snr);
      }
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return e.toString();
    }
  }

  private static int find(byte[] bytes, String tag) {
    byte[] needle = tag.getBytes(StandardCharsets.US_ASCII);
    outer:
    for (int i = 0; i <= bytes.length - needle.length; i++) {
      for (int n = 0; n < needle.length; n++) if (bytes[i + n] != needle[n]) continue outer;
      return i;
    }
    throw new AssertionError("Missing " + tag);
  }

  private static byte[] appendEmptyEdit(byte[] bytes) {
    int elst = find(bytes, "elst"), end = elst - 4 + ByteBuffer.wrap(bytes).getInt(elst - 4);
    byte[] result = new byte[bytes.length + 12];
    System.arraycopy(bytes, 0, result, 0, end);
    System.arraycopy(bytes, end, result, end + 12, bytes.length - end);
    var data = ByteBuffer.wrap(result);
    for (String box : List.of("moov", "trak", "edts", "elst")) {
      int size = find(result, box) - 4;
      data.putInt(size, data.getInt(size) + 12);
    }
    data.putInt(elst + 8, 2);
    data.putInt(end + 4, -1).putInt(end + 8, 1 << 16);
    return result;
  }
}
