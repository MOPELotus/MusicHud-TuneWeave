package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the delivered artifact without unshaded parser classes on its class path. */
class ShadedContainerTest {
  @TempDir Path temporary;

  @Test
  void isolatedArtifactRetainsAndExecutesHlsTsAndIcyApis() throws Exception {
    var urls = new ArrayList<URL>();
    urls.add(Path.of(System.getProperty("containerJar")).toUri().toURL());
    for (String path : System.getProperty("containerLibraries").split(File.pathSeparator))
      urls.add(Path.of(path).toUri().toURL());
    try (var loader =
        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      loader.setDefaultAssertionStatus(true);
      String prefix = "indi.mopelotus.musichud.audio.container.";
      var manifestType = loader.loadClass(prefix + "hls.HlsManifest");
      var manifest =
          manifestType
              .getMethod("parse", URI.class, String.class)
              .invoke(
                  null,
                  URI.create("https://example.com/index.m3u8"),
                  "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n");
      assertEquals(
          1, ((List<?>) manifest.getClass().getMethod("segments").invoke(manifest)).size());
      var resolver = loader.loadClass(prefix + "input.PlaylistResolver");
      var uri = URI.create("https://example.com/a.ts");
      assertEquals(
          uri,
          resolver
              .getMethod("validate", URI.class, URI.class, boolean.class)
              .invoke(null, uri, null, false));
      var ts = loader.loadClass(prefix + "ts.TsPacketSource");
      try (var fixture =
          ShadedContainerTest.class.getResourceAsStream("/container/ts/pes-40000.ts")) {
        assertNotNull(fixture);
        var source = ts.getConstructor(InputStream.class).newInstance(fixture);
        try {
          var packet = ts.getMethod("next").invoke(source);
          assertNotNull(packet);
          byte[] data = (byte[]) packet.getClass().getMethod("data").invoke(packet);
          assertEquals(39997, data.length);
          for (byte value : data) assertEquals((byte) 0x55, value);
          assertNull(ts.getMethod("next").invoke(source));
        } finally {
          ((AutoCloseable) source).close();
        }
      }
      var framedType = loader.loadClass(prefix + "aac.TsAdtsPacketSource");
      try (var fixture = ShadedContainerTest.class.getResourceAsStream("/container/hls/aac.ts")) {
        assertNotNull(fixture);
        var transport = ts.getConstructor(InputStream.class).newInstance(fixture);
        var framed = framedType.getConstructor(ts).newInstance(transport);
        try {
          var hash = MessageDigest.getInstance("SHA-256");
          int packets = 0, bytes = 0;
          Object packet;
          while ((packet = framedType.getMethod("next").invoke(framed)) != null) {
            var encoded = (ByteBuffer) packet.getClass().getMethod("encoded").invoke(packet);
            packets++;
            bytes += encoded.remaining();
            hash.update(encoded);
          }
          assertEquals(62, packets);
          assertEquals(20775, bytes);
          assertEquals(
              "163ceb1095ff6ca5698de68a43fd90d4e81e8bf9eb148be81a6e3da3f8bd5d13",
              HexFormat.of().formatHex(hash.digest()));
          assertEquals(0L, framedType.getMethod("timelineEpoch").invoke(framed));
        } finally {
          ((AutoCloseable) framed).close();
        }
      }
      var icy = loader.loadClass(prefix + "input.IcyMetadataInputStream");
      try (var input =
          (InputStream)
              icy.getConstructor(InputStream.class, int.class, java.util.function.Consumer.class)
                  .newInstance(
                      new ByteArrayInputStream(new byte[] {1, 2, 3, 0, 4, 5, 6}), 3, null)) {
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, input.readAllBytes());
      }
      loader.loadClass(prefix + "hls.HlsSession").getConstructor().newInstance();
      loader.loadClass(prefix + "input.HlsPlaybackInput");
      var hlsTs = loader.loadClass(prefix + "input.HlsTsPesSource");
      assertNotNull(
          hlsTs.getConstructor(
              loader.loadClass(prefix + "input.HlsPlaybackInput"),
              loader.loadClass(prefix + "input.HlsPlaybackInput$SegmentData")));
    }
  }

  @Test
  void isolatedShadedArtifactOpensAndReadsAacAndAlac() throws Exception {
    var urls = new ArrayList<URL>();
    urls.add(Path.of(System.getProperty("containerJar")).toUri().toURL());
    for (String path : System.getProperty("containerLibraries").split(File.pathSeparator))
      urls.add(Path.of(path).toUri().toURL());
    try (var loader =
        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      loader.setDefaultAssertionStatus(true);
      var type = loader.loadClass("indi.mopelotus.musichud.audio.container.Mp4PacketSource");
      for (String[] expected :
          List.of(
              new String[] {
                "aac.m4a", "8", "a9b1cbfc67e4a714d6c7e33faf18048f5173b3fcc4ee2cf83e2de168bfa7b67b"
              },
              new String[] {
                "alac.m4a", "2", "2064593ea38f039a61000afd70108e6a44c894c9574249d981cbc3f133fdcbbf"
              })) {
        Path file =
            Files.write(temporary.resolve(expected[0]), ContainerFixturesTest.fixture(expected[0]));
        Object source = type.getConstructor(Path.class).newInstance(file);
        var hash = MessageDigest.getInstance("SHA-256");
        int count = 0;
        try {
          Object packet;
          while ((packet = type.getMethod("next").invoke(source)) != null) {
            count++;
            hash.update((ByteBuffer) packet.getClass().getMethod("encoded").invoke(packet));
          }
          assertEquals(Integer.parseInt(expected[1]), count);
          assertEquals(expected[2], HexFormat.of().formatHex(hash.digest()));
        } finally {
          ((AutoCloseable) source).close();
        }
      }
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.mp4parser.IsoFile"));
      assertThrows(
          ClassNotFoundException.class,
          () ->
              loader.loadClass(
                  "com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaStreamingFile"));
    }
  }
}
