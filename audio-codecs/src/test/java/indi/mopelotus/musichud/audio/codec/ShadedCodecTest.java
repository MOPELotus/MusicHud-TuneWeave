package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShadedCodecTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "mp3",
        "mp3-raw",
        "vorbis",
        "opus",
        "integer",
        "aac",
        "aac-no-pns",
        "alac",
        "vorbis-packet",
        "opus-packet",
        "opus-six"
      })
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
      if (mode.startsWith("aac")
          || mode.equals("alac")
          || (mode.endsWith("-packet") || mode.equals("opus-six"))) {
        assertArrayEquals(
            unshaded(mode), packets(loader, mode), "Packet relocation/R8 must preserve PCM");
        return;
      }
      String method =
          mode.equals("mp3-raw")
              ? "mp3RawMedia"
              : mode.equals("integer") ? "wavpack" : mode.equals("mp3") ? "mp3" : "ogg";
      String fixture =
          mode.equals("mp3-raw")
              ? "mp3.mp3"
              : mode + (mode.equals("integer") ? ".wv" : mode.equals("mp3") ? ".mp3" : ".ogg");
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

  @Test
  void isolatedArtifactIndexDoesNotExposeOrDependOnUnshadedJLayer() throws Exception {
    byte[] encoded = CodecFixtureTest.resource("mp3.mp3");
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var indexType = loader.loadClass(prefix + "Mp3SeekIndex");
      var planType = loader.loadClass(prefix + "Mp3SeekIndex$Plan");
      var decoderType = loader.loadClass(prefix + "PcmDecoder");
      Object index =
          indexType
              .getMethod("build", InputStream.class, java.util.function.BooleanSupplier.class)
              .invoke(
                  null,
                  new ByteArrayInputStream(encoded),
                  (java.util.function.BooleanSupplier) () -> false);
      Object plan = indexType.getMethod("plan", long.class).invoke(index, 1201L);
      long offset = (long) planType.getMethod("byteOffset").invoke(plan);
      Object decoder =
          loader
              .loadClass(prefix + "JavaCodecs")
              .getMethod("mp3Indexed", InputStream.class, indexType, planType)
              .invoke(
                  null,
                  new ByteArrayInputStream(
                      Arrays.copyOfRange(encoded, (int) offset, encoded.length)),
                  index,
                  plan);
      try {
        assertEquals(1201L, decoderType.getMethod("framePosition").invoke(decoder));
        var output = new ByteArrayOutputStream();
        byte[] b = new byte[113 * 4];
        int n;
        while ((n =
                (int)
                    decoderType
                        .getMethod("readFrames", byte[].class, int.class, int.class)
                        .invoke(decoder, b, 0, 113))
            != -1) output.write(b, 0, n * 4);
        byte[] sequential = unshaded("mp3");
        assertArrayEquals(
            Arrays.copyOfRange(sequential, 1201 * 4, sequential.length), output.toByteArray());
        assertEquals(4800L, decoderType.getMethod("framePosition").invoke(decoder));
      } finally {
        decoderType.getMethod("close").invoke(decoder);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"opus", "vorbis"})
  void isolatedArtifactOggSeekReusesRawPacketCores(String codec) throws Exception {
    byte[] encoded = CodecFixtureTest.resource(codec + ".ogg");
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var sourceType = loader.loadClass(prefix + "OggSeekDecoder$Source");
      var seekerType = loader.loadClass(prefix + "OggSeekDecoder");
      var resultType = loader.loadClass(prefix + "OggSeekDecoder$Result");
      var decoderType = loader.loadClass(prefix + "PcmDecoder");
      Object source =
          java.lang.reflect.Proxy.newProxyInstance(
              loader,
              new Class<?>[] {sourceType},
              (proxy, method, args) -> {
                if (method.getName().equals("open")) return new OggSeekTest.MemoryChannel(encoded);
                throw new UnsupportedOperationException(method.getName());
              });
      Object seeker =
          seekerType
              .getConstructor(sourceType, long.class)
              .newInstance(source, (long) encoded.length);
      try {
        Object result = seekerType.getMethod("seek", long.class).invoke(seeker, 1201L);
        Object decoder = resultType.getMethod("decoder").invoke(result);
        try {
          var output = new ByteArrayOutputStream();
          byte[] b = new byte[113 * 4];
          int n;
          while ((n =
                  (int)
                      decoderType
                          .getMethod("readFrames", byte[].class, int.class, int.class)
                          .invoke(decoder, b, 0, 113))
              != -1) output.write(b, 0, n * 4);
          byte[] sequential = unshaded(codec);
          assertArrayEquals(
              Arrays.copyOfRange(sequential, 1201 * 4, sequential.length), output.toByteArray());
          assertEquals(1201L, resultType.getMethod("sourceFrame").invoke(result));
          assertEquals(3599L, decoderType.getMethod("framePosition").invoke(decoder));
        } finally {
          decoderType.getMethod("close").invoke(decoder);
        }
      } finally {
        seekerType.getMethod("close").invoke(seeker);
      }
    }
  }

  private static byte[] unshaded(String mode) throws Exception {
    if (mode.equals("opus-six")) return CodecFixtureTest.resource("opus-six.s16");
    if (mode.endsWith("-packet")) return OggPacketTest.decode(mode.replace("-packet", ""));
    try (var decoder = CodecFixtureTest.open(mode)) {
      var out = new ByteArrayOutputStream();
      byte[] b = new byte[113 * decoder.format().frameBytes()];
      int n;
      while ((n = decoder.readFrames(b, 0, 113)) != -1)
        out.write(b, 0, n * decoder.format().frameBytes());
      return out.toByteArray();
    }
  }

  static byte[] packets(ClassLoader loader, String mode) throws Exception {
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
          factory
              .getMethod(
                  mode.startsWith("aac")
                      ? "aac"
                      : mode.equals("opus-six") ? "opus" : mode.replace("-packet", ""),
                  formatType)
              .invoke(null, format);
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
