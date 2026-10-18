package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AacRecoveryArtifactTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "he-period10-sig0",
        "hev2-period10-sig0",
        "he-period72-sig1",
        "hev2-period72-sig1"
      })
  void recoverySurvivesRelocationAndR8(String name) throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      String base = "indi.mopelotus.musichud.audio.codec.";
      assertThrows(
          ClassNotFoundException.class, () -> loader.loadClass("net.sourceforge.jaad.aac.Decoder"));
      var formatType = loader.loadClass(base + "PacketSource$Format");
      var packetType = loader.loadClass(base + "PacketSource$Packet");
      var timeType = loader.loadClass(base + "PacketSource$Time");
      var api = loader.loadClass(base + "AacPacketDecoder");
      var recoveryType = loader.loadClass(base + "AacPacketDecoder$Recovery");
      var expectedType = loader.loadClass(base + "AacPacketDecoder$Expectation");
      var decodedType = loader.loadClass(base + "DecodedPacket");
      var bundle = AacRecoveryTest.bundle(name);
      int period = name.contains("72") ? 72 : 10;
      Object format =
          formatType
              .getConstructor(String.class, byte[].class)
              .newInstance("mp4a", bundle.format().codecPrivate());
      Object expected =
          expectedType
              .getConstructor(int.class, int.class, boolean.class, boolean.class)
              .newInstance(48000, 2, true, name.startsWith("hev2"));
      Object decoder =
          loader
              .loadClass(base + "PacketCodecs")
              .getMethod("aacWithRecovery", formatType, expectedType)
              .invoke(null, format, expected);
      Object zero = timeType.getField("ZERO").get(null);
      var ctor =
          packetType.getConstructor(
              formatType,
              timeType,
              timeType,
              timeType,
              timeType,
              timeType,
              boolean.class,
              ByteBuffer.class);
      try (var ordinary =
          PacketCodecs.aacWithRecovery(
              bundle.format(),
              new AacPacketDecoder.Expectation(48000, 2, true, name.startsWith("hev2")))) {
        try {
          for (int n = 1; n <= period; n++) {
            Object packet =
                ctor.newInstance(
                    format,
                    null,
                    null,
                    null,
                    zero,
                    zero,
                    false,
                    ByteBuffer.wrap(bundle.packets().get(n)));
            Object pcm = api.getMethod("decode", packetType).invoke(decoder, packet);
            var reference = ordinary.decode(bundle.packet(n));
            Object state = api.getMethod("recovery").invoke(decoder);
            assertEquals(n == period, recoveryType.getMethod("ready").invoke(state));
            assertEquals(48000, recoveryType.getMethod("observedRate").invoke(state));
            assertEquals(2, recoveryType.getMethod("observedChannels").invoke(state));
            if (n < period) {
              assertNull(pcm);
              assertTrue(((Optional<?>) api.getMethod("format").invoke(decoder)).isEmpty());
            } else {
              assertNotNull(reference);
              assertSame(packet, decodedType.getMethod("source").invoke(pcm));
              assertEquals(reference.pcm(), decodedType.getMethod("pcm").invoke(pcm));
              assertEquals(true, recoveryType.getMethod("currentAuSbrHeader").invoke(state));
            }
          }
        } finally {
          api.getMethod("close").invoke(decoder);
        }
        Object ended = api.getMethod("recovery").invoke(decoder);
        assertEquals(false, recoveryType.getMethod("valid").invoke(ended));
      }
    }
  }
}
