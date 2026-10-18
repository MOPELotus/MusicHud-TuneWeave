package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PacketMediaDecoderTest {
  @Test
  void recoveryProbeStopsBeforeLateReadinessAndReleaseDoesNotTruncatePlayback() throws Exception {
    var format = new PacketSource.Format("mp4a", new byte[0]);
    var track =
        new PacketSource.Track(
            format, null, List.of(), PacketSource.Time.ZERO, PacketSource.Time.ZERO);
    class Source implements PacketSource {
      int index, closes;

      public Track track() {
        return track;
      }

      public Packet next() {
        return index == 4 ? null : packet(format, new byte[0], index++ * 48000, 48000, false);
      }

      public Time seekMedia(Time time) {
        throw new UnsupportedOperationException();
      }

      public void close() {
        closes++;
      }
    }
    var first = new Source();
    try (var probe = new AacRecoveryWindow(first, new PacketSource.Time(1, 1))) {
      assertNotNull(probe.next());
      assertNotNull(probe.next());
      assertThrows(AacRecoveryWindow.Unavailable.class, probe::next);
      assertEquals(3, first.index);
    }
    assertEquals(1, first.closes);
    var second = new Source();
    try (var probe = new AacRecoveryWindow(second, PacketSource.Time.ZERO)) {
      assertNotNull(probe.next());
      probe.release();
      for (int i = 0; i < 3; i++) assertNotNull(probe.next());
      assertNull(probe.next());
    }
    assertEquals(1, second.closes);
  }

  @Test
  void vorbisResetSurvivesItsFirstPacketProducingNoPcm() throws Exception {
    PacketSource.Format format;
    List<byte[]> compressed = new ArrayList<>();
    try (var in =
        new DataInputStream(getClass().getResourceAsStream("/javaaudio/vorbis-raw.packets"))) {
      format = new PacketSource.Format("vorbis", in.readNBytes(in.readInt()));
      for (int count = in.readInt(); count > 0; count--)
        compressed.add(in.readNBytes(in.readInt()));
    }
    List<Integer> frameCounts = new ArrayList<>();
    try (var core = PacketCodecs.vorbis(format)) {
      for (byte[] bytes : compressed) {
        var result = core.decode(packet(format, bytes, 0, 0, false));
        frameCounts.add(result == null ? 0 : result.rawFrames());
      }
    }
    assertEquals(0, frameCounts.getFirst());
    assertTrue(frameCounts.stream().mapToInt(Integer::intValue).sum() > 0);
    List<PacketSource.Packet> packets = new ArrayList<>();
    long time = 0;
    for (int epoch = 0; epoch < 2; epoch++)
      for (int i = 0; i < compressed.size(); i++) {
        packets.add(
            packet(format, compressed.get(i), time, frameCounts.get(i), epoch > 0 && i == 0));
        time += frameCounts.get(i);
      }
    var track =
        new PacketSource.Track(
            format, null, List.of(), PacketSource.Time.ZERO, PacketSource.Time.ZERO);
    PacketSource source =
        new PacketSource() {
          int index;

          public Track track() {
            return track;
          }

          public Packet next() {
            return index == packets.size() ? null : packets.get(index++);
          }

          public Time seekMedia(Time time) throws IOException {
            throw new IOException("Not indexed");
          }

          public void close() {}
        };
    List<Boolean> observed = new ArrayList<>();
    long frames = 0;
    try (var decoder =
        new PacketMediaDecoder(
            source,
            0,
            decoded -> {
              observed.add(decoded.source().discontinuity());
              return decoded;
            })) {
      byte[] bytes = new byte[4096 * decoder.format().frameBytes()];
      for (; ; ) {
        int n = decoder.readFrames(bytes, 0, 4096);
        if (n < 0) break;
        frames += n;
      }
    }
    int each = (int) frameCounts.stream().filter(n -> n > 0).count();
    assertEquals(each * 2, observed.size());
    assertFalse(observed.get(0));
    assertTrue(observed.get(each), "The first nonempty PCM block must retain the pending reset");
    assertEquals(1, observed.stream().filter(Boolean::booleanValue).count());
    assertEquals(time, frames);
  }

  private static PacketSource.Packet packet(
      PacketSource.Format format, byte[] bytes, long pts, long frames, boolean reset) {
    return new PacketSource.Packet(
        format,
        new PacketSource.Time(pts, 48000),
        new PacketSource.Time(pts, 48000),
        new PacketSource.Time(frames, 48000),
        PacketSource.Time.ZERO,
        PacketSource.Time.ZERO,
        reset,
        ByteBuffer.wrap(bytes));
  }
}
