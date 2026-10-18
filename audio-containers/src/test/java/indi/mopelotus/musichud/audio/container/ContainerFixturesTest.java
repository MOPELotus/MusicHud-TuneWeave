package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerFixturesTest {
  @TempDir Path temporary;

  static byte[] fixture(String name) throws IOException {
    try (var input = ContainerFixturesTest.class.getResourceAsStream("/containers/" + name)) {
      if (input == null) throw new IOException("Missing fixture " + name);
      return input.readAllBytes();
    }
  }

  Path file(String name) throws IOException {
    return Files.write(temporary.resolve(name), fixture(name));
  }

  static String packetHash(PacketSource source, int count) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    int actual = 0;
    PacketSource.Packet packet;
    while ((packet = source.next()) != null) {
      digest.update(packet.encoded());
      actual++;
    }
    assertEquals(count, actual);
    return HexFormat.of().formatHex(digest.digest());
  }

  @Test
  void aacPreservesRawConfigurationPacketsAndMediaClock() throws Exception {
    try (var source = new Mp4PacketSource(file("aac.m4a"))) {
      assertEquals("mp4a", source.track().initialFormat().codec());
      assertArrayEquals(
          HexFormat.of().parseHex("119056e500"), source.track().initialFormat().codecPrivate());
      assertEquals(48000, source.track().mediaDuration().timescale());
      assertFalse(source.track().edits().isEmpty());
      assertEquals(
          "a9b1cbfc67e4a714d6c7e33faf18048f5173b3fcc4ee2cf83e2de168bfa7b67b",
          packetHash(source, 8));
    }
  }

  @Test
  void alacPreservesFullRawConfigurationBoxAndPackets() throws Exception {
    try (var source = new Mp4PacketSource(file("alac.m4a"))) {
      assertEquals("alac", source.track().initialFormat().codec());
      assertArrayEquals(
          HexFormat.of()
              .parseHex("00000024616c616300000000000010000010280a0e02000000004004001770000000bb80"),
          source.track().initialFormat().codecPrivate());
      assertEquals(
          "2064593ea38f039a61000afd70108e6a44c894c9574249d981cbc3f133fdcbbf",
          packetHash(source, 2));
    }
  }

  @Test
  void sampleDescriptionSwitchPreservesEachConfigurationAndResetsOnce() throws Exception {
    String[] configs = {
      "00000024616c616300000000000010000010280a0e02000000004004001770000000bb80",
      "00000024616c616300000000000010000018280a0e02000000006004002328000000bb80"
    };
    String[] hashes = {
      "c4dc3ca63fb125a140358ab7ad7f0f38a47e7c08ae80d54bed41b2386950e25b",
      "090c9b9620eccebc186c11dd18e928590d4785caeb4a35e3d9523b453965e34a",
      "d500903c38a1190214c1718db54a06bfeb7f37ab8057d622589dce297836630b",
      "218dc78486423524881be9da587ba930a58a81d4fcd05e0fd4504e17a7de61ef"
    };
    try (var source = new Mp4PacketSource(file("alac-description-switch.m4a"))) {
      for (int i = 0; i < hashes.length; i++) {
        var packet = source.next();
        assertNotNull(packet);
        assertArrayEquals(HexFormat.of().parseHex(configs[i / 2]), packet.format().codecPrivate());
        assertEquals(i == 2, packet.discontinuity());
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(packet.encoded());
        assertEquals(hashes[i], HexFormat.of().formatHex(digest.digest()));
        byte[] mutable = packet.format().codecPrivate();
        Arrays.fill(mutable, (byte) 0);
        assertArrayEquals(HexFormat.of().parseHex(configs[i / 2]), packet.format().codecPrivate());
      }
      assertNull(source.next());
    }
  }

  @Test
  void initialFormatFollowsFirstPacketDescriptionRatherThanFirstStsdEntry() throws Exception {
    byte[] bytes = fixture("alac-description-switch.m4a");
    int stsd = findTag(bytes, "stsd");
    int entries = stsd + 12;
    int firstSize = ByteBuffer.wrap(bytes).getInt(entries);
    int secondSize = ByteBuffer.wrap(bytes).getInt(entries + firstSize);
    byte[] first = Arrays.copyOfRange(bytes, entries, entries + firstSize);
    byte[] second =
        Arrays.copyOfRange(bytes, entries + firstSize, entries + firstSize + secondSize);
    System.arraycopy(second, 0, bytes, entries, second.length);
    System.arraycopy(first, 0, bytes, entries + second.length, first.length);
    int stsc = findTag(bytes, "stsc");
    ByteBuffer.wrap(bytes).putInt(stsc + 20, 2).putInt(stsc + 32, 1);
    Path file = Files.write(temporary.resolve("first-description-two.m4a"), bytes);
    try (var source = new Mp4PacketSource(file)) {
      var initial = source.track().initialFormat();
      assertArrayEquals(
          HexFormat.of()
              .parseHex("00000024616c616300000000000010000010280a0e02000000004004001770000000bb80"),
          initial.codecPrivate());
      var firstPacket = source.next();
      assertEquals(initial, firstPacket.format());
      assertFalse(firstPacket.discontinuity());
      source.next();
      var switched = source.next();
      assertTrue(switched.discontinuity());
      assertNotEquals(initial, switched.format());
    }
  }

  private static int findTag(byte[] bytes, String tag) {
    byte[] needle = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    for (int i = 0; i < bytes.length - needle.length; i++) {
      boolean same = true;
      for (int n = 0; n < needle.length; n++) if (bytes[i + n] != needle[n]) same = false;
      if (same) return i;
    }
    throw new AssertionError("Missing box " + tag);
  }

  @Test
  void quickTimeMp3EntryNormalizesWithoutChangingPackets() throws Exception {
    byte[] bytes = fixture("mp3.mp4");
    int entry = findTag(bytes, "mp4a");
    System.arraycopy(
        ".mp3".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, entry, 4);
    Path original = file("mp3.mp4");
    Path quickTime = Files.write(temporary.resolve("quicktime.mp4"), bytes);
    try (var expected = new Mp4PacketSource(original);
        var actual = new Mp4PacketSource(quickTime)) {
      assertEquals("mp3", actual.track().initialFormat().codec());
      PacketSource.Packet packet;
      while ((packet = expected.next()) != null) {
        var other = actual.next();
        assertNotNull(other);
        assertEquals("mp3", other.format().codec());
        assertEquals(packet.encoded(), other.encoded());
      }
      assertNull(actual.next());
    }
  }

  @Test
  void matroskaRetainsDelayPrerollAndTailDiscard() throws Exception {
    var input = new ContainerLifecycleTest.Memory(fixture("opus.webm"));
    try (var source = new MatroskaPacketSource(input)) {
      assertEquals("opus", source.track().initialFormat().codec());
      assertEquals(
          0, source.track().codecDelay().compareValue(new PacketSource.Time(6500000, 1000000000)));
      assertEquals(
          0,
          source.track().seekPreRoll().compareValue(new PacketSource.Time(80000000, 1000000000)));
      PacketSource.Packet packet, last = null;
      int count = 0;
      var digest = MessageDigest.getInstance("SHA-256");
      while ((packet = source.next()) != null) {
        count++;
        digest.update(packet.encoded());
        last = packet;
      }
      assertEquals(7, count);
      assertEquals(
          "7737e0bc3a99f742ec0a2f992391a21a1c7e1083716fb31c02bffb88c204f400",
          HexFormat.of().formatHex(digest.digest()));
      assertNotNull(last);
      assertEquals(0, last.discardEnd().compareValue(new PacketSource.Time(3500000, 1000000000)));
    }
    assertEquals(1, input.closes);
  }

  @Test
  void unknownSizedSegmentAndClusterPreservePackets() throws Exception {
    byte[] bytes = fixture("opus.webm");
    for (byte[] tag :
        List.of(HexFormat.of().parseHex("18538067"), HexFormat.of().parseHex("1f43b675"))) {
      for (int i = 0; i < bytes.length - tag.length; i++) {
        boolean match = true;
        for (int n = 0; n < tag.length; n++) if (bytes[i + n] != tag[n]) match = false;
        if (!match) continue;
        int at = i + tag.length, width = Integer.numberOfLeadingZeros(bytes[at] & 255) - 23;
        bytes[at] = (byte) ((1 << (9 - width)) - 1);
        Arrays.fill(bytes, at + 1, at + width, (byte) 255);
        i = at + width - 1;
      }
    }
    try (var source = new MatroskaPacketSource(new ContainerLifecycleTest.Memory(bytes))) {
      assertEquals(
          "7737e0bc3a99f742ec0a2f992391a21a1c7e1083716fb31c02bffb88c204f400",
          packetHash(source, 7));
    }
  }
}
