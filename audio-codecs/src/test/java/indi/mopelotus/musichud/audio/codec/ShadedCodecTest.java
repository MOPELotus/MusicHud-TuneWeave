package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShadedCodecTest {
  @ParameterizedTest
  @ValueSource(strings = {"mp3", "vorbis", "opus", "integer", "aac", "aac-no-pns", "alac"})
  void isolatedArtifactPreservesPcmAndPrivateResources(String mode) throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      assertThrows(
          ClassNotFoundException.class, () -> loader.loadClass("javazoom.jl.decoder.Decoder"));
      assertNotNull(
          loader.getResource(
              "indi/mopelotus/musichud/audio/codec/shaded/javazoom/jl/decoder/sfd.ser"));
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var factory = loader.loadClass(prefix + "JavaCodecs");
      var api = loader.loadClass(prefix + "PcmDecoder");
      if (mode.startsWith("aac") || mode.equals("alac")) {
        assertArrayEquals(
            unshaded(mode), packets(loader, mode), "Packet relocation/R8 must preserve PCM");
        return;
      }
      String method = mode.equals("integer") ? "wavpack" : mode.equals("mp3") ? "mp3" : "ogg";
      String fixture =
          mode + (mode.equals("integer") ? ".wv" : mode.equals("mp3") ? ".mp3" : ".ogg");
      Object decoder =
          factory
              .getMethod(method, InputStream.class)
              .invoke(null, new ByteArrayInputStream(CodecFixtureTest.resource(fixture)));
      byte[] actual;
      try {
        Object format = api.getMethod("format").invoke(decoder);
        int frameBytes = (int) format.getClass().getMethod("frameBytes").invoke(format);
        var read = api.getMethod("readFrames", byte[].class, int.class, int.class);
        var out = new ByteArrayOutputStream();
        byte[] b = new byte[113 * frameBytes];
        int n;
        while ((n = (int) read.invoke(decoder, b, 0, 113)) != -1) out.write(b, 0, n * frameBytes);
        actual = out.toByteArray();
      } finally {
        api.getMethod("close").invoke(decoder);
      }
      try (var unshaded = CodecFixtureTest.open(mode)) {
        var out = new ByteArrayOutputStream();
        byte[] b = new byte[113 * unshaded.format().frameBytes()];
        int n;
        while ((n = unshaded.readFrames(b, 0, 113)) != -1)
          out.write(b, 0, n * unshaded.format().frameBytes());
        assertArrayEquals(out.toByteArray(), actual, "Relocation/R8 must preserve codec output");
      }
    }
  }

  private static byte[] unshaded(String mode) throws Exception {
    try (var decoder = CodecFixtureTest.open(mode)) {
      var out = new ByteArrayOutputStream();
      byte[] b = new byte[113 * decoder.format().frameBytes()];
      int n;
      while ((n = decoder.readFrames(b, 0, 113)) != -1)
        out.write(b, 0, n * decoder.format().frameBytes());
      return out.toByteArray();
    }
  }

  private static byte[] packets(ClassLoader loader, String mode) throws Exception {
    String base = "indi.mopelotus.musichud.audio.codec.";
    var formatType = loader.loadClass(base + "PacketSource$Format");
    var packetType = loader.loadClass(base + "PacketSource$Packet");
    var timeType = loader.loadClass(base + "PacketSource$Time");
    var decoderType = loader.loadClass(base + "PacketDecoder");
    var decodedType = loader.loadClass(base + "DecodedPacket");
    var factory = loader.loadClass(base + "PacketCodecs");
    Object zero = timeType.getField("ZERO").get(null);
    try (var source = CodecFixtureTest.packets(mode)) {
      Object format =
          formatType
              .getConstructor(String.class, byte[].class)
              .newInstance(
                  source.track().initialFormat().codec(),
                  source.track().initialFormat().codecPrivate());
      Object decoder =
          factory.getMethod(mode.startsWith("aac") ? "aac" : mode, formatType).invoke(null, format);
      var packetConstructor =
          packetType.getConstructor(
              formatType,
              timeType,
              timeType,
              timeType,
              timeType,
              timeType,
              boolean.class,
              java.nio.ByteBuffer.class);
      var out = new ByteArrayOutputStream();
      PacketSource.Packet packet;
      try {
        while ((packet = source.next()) != null) {
          Object input =
              packetConstructor.newInstance(
                  format, null, null, null, zero, zero, false, packet.encoded());
          Object decoded = decoderType.getMethod("decode", packetType).invoke(decoder, input);
          if (decoded != null) {
            var pcm = (java.nio.ByteBuffer) decodedType.getMethod("pcm").invoke(decoded);
            byte[] b = new byte[pcm.remaining()];
            pcm.get(b);
            out.write(b);
          }
        }
        assertNull(decoderType.getMethod("finish").invoke(decoder));
      } finally {
        decoderType.getMethod("close").invoke(decoder);
      }
      return out.toByteArray();
    }
  }
}
