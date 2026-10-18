package indi.mopelotus.musichud.audio.container.aac;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.ts.TsPacketSource;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdtsFixtureTest {
  @Test
  void actualAacTsMatchesIndependentFfmpegRawAccessUnits() throws Exception {
    var input = getClass().getResourceAsStream("/container/hls/aac.ts");
    assertNotNull(input);
    try (var source = new TsAdtsPacketSource(new TsPacketSource(input))) {
      var digest = MessageDigest.getInstance("SHA-256");
      int packets = 0, bytes = 0;
      PacketSource.Packet packet;
      while ((packet = source.next()) != null) {
        packets++;
        bytes += packet.encoded().remaining();
        digest.update(packet.encoded());
      }
      assertEquals(62, packets);
      assertEquals(20775, bytes);
      assertEquals(
          "163ceb1095ff6ca5698de68a43fd90d4e81e8bf9eb148be81a6e3da3f8bd5d13",
          HexFormat.of().formatHex(digest.digest()));
    }
  }

  @Test
  void pmtPrefersSupportedAdtsRegardlessOfLatmOrder() throws Exception {
    for (String name : List.of("pmt-15-first", "pmt-17-first")) {
      var input = getClass().getResourceAsStream("/container/ts/" + name + ".ts");
      assertNotNull(input);
      try (var source = new TsPacketSource(input)) {
        var packet = source.next();
        assertNotNull(packet);
        assertEquals(15, packet.streamType());
        assertEquals(97, packet.data().length);
        for (byte value : packet.data()) assertEquals((byte) 0x55, value);
        assertNull(source.next());
      }
    }
  }
}
