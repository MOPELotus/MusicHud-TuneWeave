package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static indi.mopelotus.musichud.client.audio.decoder.javaaudio.HlsTsPacketSourceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HlsDynamicPacketTimelineTest {
  @Test
  void genuineEpochFormatChangesKeepOneExactProgrammeClock() throws Exception {
    try (MediaTransport transport = new MediaTransport()) {
      var fetcher = new TransportHlsFetcher(transport, uri -> Map.of());
      URI uri = FIXTURES.resolve("dynamic/dynamic.m3u8").toUri();
      byte[] pcm = decode(uri, fetcher);
      PacketSource.Time end = PacketSource.Time.ZERO;
      int changes = 0;
      PacketSource.Format previous = null;
      try (var source = packets(uri, fetcher)) {
        for (var packet = source.next(); packet != null; packet = source.next()) {
          if (previous != null && !previous.equals(packet.format())) {
            assertEquals(0, packet.presentationTime().compareValue(end));
            changes++;
          }
          previous = packet.format();
          end = add(packet.presentationTime(), packet.duration());
        }
      }
      assertEquals(3, changes);
      assertEquals(PresentationPlan.framesCeil(end, 48000), pcm.length / 8);
      if (System.getProperty("audio.test.output") != null) {
        java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("audio.test.output"));
        Files.createDirectories(output);
        Files.write(output.resolve("hls-dynamic.f32"), pcm);
      }
    }
  }
}
