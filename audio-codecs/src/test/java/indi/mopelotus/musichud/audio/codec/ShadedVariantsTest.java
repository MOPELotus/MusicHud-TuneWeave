package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShadedVariantsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "wma/noise-v1.wma",
        "ape/synthetic-24-2.ape",
        "variants/float-eight.wv",
        "variants/integer-eight.wv",
        "variants/hybrid-float-six.wv",
        "variants/aiff-s24be-2ch-97f.aiff",
        "variants/au-6-f32-2ch-97f.au"
      })
  void finalPrivateArtifactMatchesIndependentVectors(String resource) throws Exception {
    String method =
        resource.endsWith(".wma")
            ? "wma"
            : resource.endsWith(".ape") ? "ape" : resource.endsWith(".wv") ? "wavpack" : "aiffAu";
    try (var loader =
        new URLClassLoader(
            new URL[] {Path.of(System.getProperty("audioCodecJar")).toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var factory = loader.loadClass(prefix + "JavaCodecs");
      var api = loader.loadClass(prefix + "PcmDecoder");
      assertThrows(
          ClassNotFoundException.class,
          () -> loader.loadClass("com.sedmelluq.discord.lavaplayer.natives.wma.WmaDecoder"));
      Object decoder =
          factory
              .getMethod(method, InputStream.class)
              .invoke(null, new ByteArrayInputStream(resource(resource)));
      var out = new ByteArrayOutputStream();
      try {
        Object format = api.getMethod("format").invoke(decoder);
        int frameBytes = (int) format.getClass().getMethod("frameBytes").invoke(format);
        if (resource.endsWith("eight.wv")) {
          Object speakers = format.getClass().getMethod("speakers").invoke(format);
          assertEquals(255L, speakers.getClass().getMethod("mask").invoke(speakers));
        }
        var read = api.getMethod("readFrames", byte[].class, int.class, int.class);
        byte[] b = new byte[113 * frameBytes];
        int n;
        while ((n = (int) read.invoke(decoder, b, 0, 113)) != -1) out.write(b, 0, n * frameBytes);
      } finally {
        api.getMethod("close").invoke(decoder);
      }
      String expected =
          resource.endsWith(".wma")
              ? resource.replace(".wma", ".f32")
              : resource.endsWith(".ape")
                  ? resource.replace(".ape", ".pcm")
                  : resource.endsWith(".wv") ? resource.replace(".wv", ".pcm") : resource + ".pcm";
      byte[] actual = out.toByteArray(), reference = resource(expected);
      if (resource.endsWith(".wma")) {
        assertEquals(reference.length, actual.length);
        var a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var r = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        while (a.hasRemaining()) assertEquals(r.get(), a.get(), 2e-7);
      } else assertArrayEquals(reference, actual);
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (var in = ShadedVariantsTest.class.getResourceAsStream("/codecs/" + name)) {
      if (in == null) throw new FileNotFoundException(name);
      return in.readAllBytes();
    }
  }
}
