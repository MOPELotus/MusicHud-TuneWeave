package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static indi.mopelotus.musichud.client.audio.decoder.javaaudio.HlsTsPacketSourceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.container.Mp4PacketSource;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsFmp4PacketSourceTest {
  @TempDir Path temporary;
  static final String ORDINARY = "hls-input-fmp4-study/", PRIMED = "hls-input-fmp4-primed-study/";

  static byte[] pcm(PcmDecoder source) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] block = new byte[4096 * source.format().frameBytes()];
    for (int n; (n = source.readFrames(block, 0, 4096)) >= 0; )
      out.write(block, 0, n * source.format().frameBytes());
    return out.toByteArray();
  }

  static byte[] presented(URI uri, TransportHlsFetcher fetcher) throws IOException {
    PresentationPlan plan;
    try (var raw = new PacketMediaDecoder(packets(uri, fetcher))) {
      plan = PresentationPlan.from(raw.track(), raw.format().sampleRate(), false);
    }
    try (var decoder = new PresentedDecoder(factory(uri, fetcher), plan)) {
      return pcm(decoder);
    }
  }

  @Test
  void ordinarySegmentsKeepCodecStateAndZeroHeaderEditDoesNotEmptyProgramme() throws Exception {
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    try (MediaTransport transport = new MediaTransport();
        Server server =
            new Server(
                exchange -> {
                  opens.incrementAndGet();
                  file(exchange);
                })) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      URI local = FIXTURES.resolve(ORDINARY + "media.m3u8").toUri();
      byte[] hls = decode(local, fetcher);
      if (System.getProperty("audio.test.output") != null) {
        Path output = Path.of(System.getProperty("audio.test.output"));
        Files.createDirectories(output);
        Files.write(output.resolve("hls-fmp4.f32"), hls);
      }

      assertArrayEquals(hls, decode(server.uri("/" + ORDINARY + "media.m3u8"), fetcher));
      assertEquals(4, opens.get());
      assertArrayEquals(hls, presented(local, fetcher));
      try (var source = packets(local, fetcher)) {
        assertTrue(source.track().edits().isEmpty());
        assertEquals(0, source.track().codecDelay().ticks());
        int resets = 0;
        PacketSource.Time previousEnd = null;
        int count = 0;
        for (var p = source.next(); p != null; p = source.next()) {
          if (previousEnd != null) assertEquals(0, p.presentationTime().compareValue(previousEnd));
          previousEnd = add(p.presentationTime(), p.duration());
          if (p.discontinuity()) resets++;
          count++;
        }
        assertEquals(0, resets);
        assertEquals(62, count);
        assertEquals(PresentationPlan.framesCeil(previousEnd, 48000), hls.length / 8);
      }
      Path joined = temporary.resolve("joined.mp4");
      try (var out = Files.newOutputStream(joined)) {
        for (String file : List.of("init.mp4", "media0.m4s", "media1.m4s"))
          Files.copy(FIXTURES.resolve(ORDINARY + file), out);
      }
      try (var raw = new PacketMediaDecoder(new Mp4PacketSource(joined))) {
        assertArrayEquals(pcm(raw), hls);
      }
    }
  }

  @Test
  void openEndedPrimingOffsetIsAppliedOnceForTheWholePlaylist() throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      URI raw = FIXTURES.resolve(ORDINARY + "media.m3u8").toUri(),
          primed = FIXTURES.resolve(PRIMED + "media.m3u8").toUri();
      byte[] original = presented(raw, fetcher), trimmed = presented(primed, fetcher);
      assertArrayEquals(Arrays.copyOfRange(original, 1024 * 8, original.length), trimmed);
      try (var source = packets(primed, fetcher)) {
        assertTrue(source.track().edits().isEmpty());
        assertEquals(
            0, source.track().codecDelay().compareValue(new PacketSource.Time(1024, 48000)));
      }
    }
  }

  @Test
  void genuineIdentityEditEpochJoinsWithoutDroppingOrAddingFrames() throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      byte[] once = presented(FIXTURES.resolve(ORDINARY + "media.m3u8").toUri(), fetcher),
          twice = presented(FIXTURES.resolve(ORDINARY + "repeat.m3u8").toUri(), fetcher);
      var expected = new ByteArrayOutputStream();
      expected.write(once);
      expected.write(once);
      assertArrayEquals(expected.toByteArray(), twice);
    }
  }

  @Test
  void primedReopenSeekMatchesCompletePresentedPcm() throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      URI uri = FIXTURES.resolve(PRIMED + "media.m3u8").toUri();
      byte[] full = presented(uri, fetcher);
      long frames = full.length / 8;
      PresentationPlan plan;
      try (var raw = new PacketMediaDecoder(packets(uri, fetcher))) {
        plan = PresentationPlan.from(raw.track(), raw.format().sampleRate(), false);
      }
      try (var decoder = new PresentedDecoder(factory(uri, fetcher), plan)) {
        for (long target : new long[] {0, frames - 51, 13000, 37}) {
          assertTrue(decoder.seekFrame(target));
          byte[] block = new byte[37 * 8];
          int read = 0;
          while (read < 37) {
            int n = decoder.readFrames(block, read * 8, 37 - read);
            if (n < 0) break;
            read += n;
          }
          assertEquals(37, read);
          assertArrayEquals(
              Arrays.copyOfRange(
                  full, Math.toIntExact(target * 8), Math.toIntExact((target + 37) * 8)),
              block);
        }
      }
    }
  }

  @Test
  void nonidentityEditAcrossNewPeriodFailsExplicitlyInsteadOfReapplyingGlobalTrim()
      throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      try (var source = packets(FIXTURES.resolve(PRIMED + "repeat.m3u8").toUri(), fetcher)) {
        assertThrows(
            IOException.class,
            () -> {
              while (source.next() != null) {}
            });
      }
    }
  }
}
