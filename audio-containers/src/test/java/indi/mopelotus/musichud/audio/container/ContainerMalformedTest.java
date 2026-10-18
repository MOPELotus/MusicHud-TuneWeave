package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerMalformedTest {
  @TempDir Path temporary;

  @Test
  void hostileMp4TablesRejectBeforeExpandedAllocation() throws Exception {
    byte[] original = ContainerFixturesTest.fixture("aac.m4a");
    int cases = 0;
    for (String table :
        List.of("stsz:12", "stts:8", "stsc:8", "stco:8", "stts:12", "stsz:16", "stco:12")) {
      String[] parts = table.split(":");
      int location = find(original, parts[0].getBytes(StandardCharsets.US_ASCII));
      assertTrue(location > 0);
      for (int value : List.of(Integer.MAX_VALUE, 0x1000000)) {
        byte[] bytes = original.clone();
        ByteBuffer.wrap(bytes).putInt(location + Integer.parseInt(parts[1]), value);
        Path file = Files.write(temporary.resolve("table-" + cases++ + ".m4a"), bytes);
        assertThrows(
            IOException.class,
            () -> {
              try (var source = new Mp4PacketSource(file)) {
                while (source.next() != null) {}
              }
            },
            table);
      }
    }
    assertEquals(14, cases);
  }

  @Test
  void fixedSampleSizeStillBoundsExpandedSampleCount() throws Exception {
    byte[] bytes = ContainerFixturesTest.fixture("aac.m4a");
    int location = find(bytes, "stsz".getBytes(StandardCharsets.US_ASCII));
    ByteBuffer.wrap(bytes).putInt(location + 8, 1).putInt(location + 12, 0x1000000);
    Path file = Files.write(temporary.resolve("fixed.m4a"), bytes);
    assertThrows(IOException.class, () -> new Mp4PacketSource(file));
  }

  private static int find(byte[] bytes, byte[] needle) {
    for (int i = 0; i <= bytes.length - needle.length; i++) {
      boolean match = true;
      for (int n = 0; n < needle.length; n++) if (bytes[i + n] != needle[n]) match = false;
      if (match) return i;
    }
    return -1;
  }
}
