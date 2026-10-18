package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatroskaClockMetadataTest {
  @Test
  void actualShadedArtifactExposesClockMetadataWithAssertionsEnabledAndDisabled() throws Exception {
    var urls = new ArrayList<java.net.URL>();
    urls.add(java.nio.file.Path.of(System.getProperty("containerJar")).toUri().toURL());
    for (String path : System.getProperty("containerLibraries").split(java.io.File.pathSeparator))
      urls.add(java.nio.file.Path.of(path).toUri().toURL());
    // Only reuse the test byte-array input, resolved against the delivered container's own API.
    urls.add(
        ContainerLifecycleTest.Memory.class.getProtectionDomain().getCodeSource().getLocation());
    for (boolean assertions : new boolean[] {false, true}) {
      try (var loader =
          new java.net.URLClassLoader(
              urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
        loader.setDefaultAssertionStatus(assertions);
        String prefix = "indi.mopelotus.musichud.audio.container.";
        var memory = loader.loadClass(prefix + "ContainerLifecycleTest$Memory");
        var constructor = memory.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        Object input = constructor.newInstance((Object) ContainerFixturesTest.fixture("opus.webm"));
        var type = loader.loadClass(prefix + "MatroskaPacketSource");
        var source =
            type.getConstructor(loader.loadClass(prefix + "io.SeekableInputStream"))
                .newInstance(input);
        try {
          Object quantum = type.getMethod("timestampQuantum").invoke(source);
          assertEquals(1_000_000L, quantum.getClass().getMethod("ticks").invoke(quantum));
          assertEquals(1_000_000_000L, quantum.getClass().getMethod("timescale").invoke(quantum));
          Object track = type.getMethod("track").invoke(source);
          assertNull(track.getClass().getMethod("mediaDuration").invoke(track));
          assertNotNull(type.getMethod("estimatedDuration").invoke(source));
          Object packet = type.getMethod("next").invoke(source);
          Object pts = packet.getClass().getMethod("presentationTime").invoke(packet);
          assertEquals(-6_500_000L, pts.getClass().getMethod("ticks").invoke(pts));
        } finally {
          ((AutoCloseable) source).close();
        }
      }
    }
  }

  @Test
  void segmentDurationIsEstimateAndPresentationTimestampAlreadySubtractsCodecDelay()
      throws Exception {
    try (var source =
        new MatroskaPacketSource(
            new ContainerLifecycleTest.Memory(ContainerFixturesTest.fixture("opus.webm")))) {
      assertNull(source.track().mediaDuration(), "Rounded header duration is not a PCM endpoint");
      assertNotNull(source.estimatedDuration());
      assertTrue(source.estimatedDuration().ticks() > 0);
      assertEquals(0, source.timestampQuantum().compareValue(new PacketSource.Time(1, 1000)));
      var first = source.next();
      assertNotNull(first);
      assertEquals(
          0,
          first
              .presentationTime()
              .compareValue(
                  new PacketSource.Time(
                      -source.track().codecDelay().ticks(),
                      source.track().codecDelay().timescale())));
      assertTrue(first.presentationTime().ticks() < 0);
    }
  }

  @Test
  void publicTimestampQuantumFollowsActualSegmentClockAndPreservesDelaySubtraction()
      throws Exception {
    byte[] original = ContainerFixturesTest.fixture("opus.webm");
    byte[] changed = original.clone();
    byte[] clock = HexFormat.of().parseHex("2ad7b1830f4240");
    int at = -1;
    for (int i = 0; i <= changed.length - clock.length; i++) {
      if (Arrays.equals(changed, i, i + clock.length, clock, 0, clock.length)) {
        at = i;
        break;
      }
    }
    assertTrue(at >= 0, "Fixture contains an explicit one millisecond clock");
    System.arraycopy(HexFormat.of().parseHex("1e8480"), 0, changed, at + 4, 3);
    try (var a = new MatroskaPacketSource(new ContainerLifecycleTest.Memory(original));
        var b = new MatroskaPacketSource(new ContainerLifecycleTest.Memory(changed))) {
      assertEquals(0, b.timestampQuantum().compareValue(new PacketSource.Time(2, 1000)));
      assertNull(b.track().mediaDuration());
      assertEquals(
          0,
          b.estimatedDuration()
              .compareValue(
                  new PacketSource.Time(
                      Math.multiplyExact(a.estimatedDuration().ticks(), 2),
                      a.estimatedDuration().timescale())));
      for (int i = 0; i < 2; i++) {
        var x = a.next();
        var y = b.next();
        assertEquals(x.encoded(), y.encoded());
        assertEquals(
            Math.multiplyExact(x.presentationTime().ticks() + a.track().codecDelay().ticks(), 2),
            y.presentationTime().ticks() + b.track().codecDelay().ticks());
      }
    }
  }
}
