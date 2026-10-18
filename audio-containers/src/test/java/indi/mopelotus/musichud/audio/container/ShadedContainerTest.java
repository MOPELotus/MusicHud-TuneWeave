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
