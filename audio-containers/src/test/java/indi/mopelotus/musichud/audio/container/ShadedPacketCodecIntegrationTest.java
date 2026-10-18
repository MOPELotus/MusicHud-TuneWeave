package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadedPacketCodecIntegrationTest {
  @TempDir Path temporary;

  @Test
  void realShadedContainersAndCodecsMatchIndependentPcmWithAndWithoutAssertions() throws Exception {
    var cases = new LinkedHashMap<String, String>();
    cases.put("alac.m4a", "e849dae206e318e7a6bd437fbf0391066548a557964c04e6f9fc819bad92ad6f");
    cases.put("alac20.m4a", "a05aa4c916d71680bc98dad794f1b65743469f6911660c85a562444557b6569b");
    cases.put("alac24.m4a", "1c5ea7337aa3f3171e43a1ba987b5722ba6675301cac0077bf9c246b0189a51b");
    cases.put(
        "alac-description-switch.m4a",
        "321dab4dfbbf84d40f61d50c2bc3adf1fdc30290a2967f972866e4946b163687");
    cases.put("first-description-two.m4a", cases.get("alac-description-switch.m4a"));
    cases.put("aac.m4a", null);
    cases.put("mp3.mp4", null);
    cases.put("quicktime-mp3.mp4", null);
    for (var name : cases.keySet()) {
      byte[] bytes =
          ContainerFixturesTest.fixture(
              name.equals("first-description-two.m4a")
                  ? "alac-description-switch.m4a"
                  : name.equals("quicktime-mp3.mp4") ? "mp3.mp4" : name);
      if (name.equals("first-description-two.m4a")) reverseSampleDescriptions(bytes);
      if (name.equals("quicktime-mp3.mp4"))
        System.arraycopy(
            ".mp3".getBytes(StandardCharsets.US_ASCII), 0, bytes, find(bytes, "mp4a"), 4);
      Files.write(temporary.resolve(name), bytes);
    }
    for (boolean assertions : List.of(false, true))
      for (var entry : cases.entrySet()) {
        String name = entry.getKey();
        Path output = temporary.resolve(name + "-" + assertions + ".pcm"),
            log = temporary.resolve(name + "-" + assertions + ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes =
            Path.of(
                    ShadedPacketPcmProbe.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var process =
            new ProcessBuilder(
                    java,
                    assertions ? "-ea" : "-da",
                    "-cp",
                    classes,
                    ShadedPacketPcmProbe.class.getName(),
                    System.getProperty("containerJar"),
                    System.getProperty("packetCodecJar"),
                    System.getProperty("packetHostSlf4jJar"),
                    temporary.resolve(name).toString(),
                    output.toString(),
                    Boolean.toString(assertions))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          fail("Packet decoding timed out: " + name);
        }
        assertEquals(
            0, process.exitValue(), () -> name + " assertions=" + assertions + "\n" + read(log));
        byte[] actual = Files.readAllBytes(output);
        if (entry.getValue() != null)
          assertEquals(
              entry.getValue(),
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)),
              name);
        else {
          byte[] expected =
              ContainerFixturesTest.fixture(
                  name.equals("aac.m4a") ? "aac.ffmpeg.s16le" : "mp3.ffmpeg.s16le");
          assertEquals(expected.length, actual.length);
          double error = 0, signal = 0;
          var a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
          var b = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
          while (a.hasRemaining()) {
            int x = a.getShort(), y = b.getShort();
            error += (double) (x - y) * (x - y);
            signal += (double) y * y;
          }
          double snr = 10 * Math.log10(signal / Math.max(1, error));
          assertTrue(snr > 45, name + " independent PCM SNR " + snr);
        }
      }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return e.toString();
    }
  }

  private static void reverseSampleDescriptions(byte[] bytes) {
    int stsd = find(bytes, "stsd"),
        at = stsd + 12,
        first = ByteBuffer.wrap(bytes).getInt(at),
        second = ByteBuffer.wrap(bytes).getInt(at + first);
    byte[] a = Arrays.copyOfRange(bytes, at, at + first),
        b = Arrays.copyOfRange(bytes, at + first, at + first + second);
    System.arraycopy(b, 0, bytes, at, b.length);
    System.arraycopy(a, 0, bytes, at + b.length, a.length);
    int stsc = find(bytes, "stsc");
    ByteBuffer.wrap(bytes).putInt(stsc + 20, 2).putInt(stsc + 32, 1);
  }

  private static int find(byte[] bytes, String tag) {
    byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < bytes.length - t.length; i++) {
      boolean same = true;
      for (int n = 0; n < t.length; n++) if (bytes[i + n] != t[n]) same = false;
      if (same) return i;
    }
    throw new AssertionError("Missing " + tag);
  }
}
