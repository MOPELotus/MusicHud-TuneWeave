package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class AacSbrReferenceTest {
  @Test
  void inheritedShortEnvelopeMatchesIndependentReferenceBeforeAndAfterR8() throws Exception {
    String fixture = "aac-sbr-gain/transient";
    byte[] actual = ShadedCodecTest.packets(getClass().getClassLoader(), fixture);
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var isolated = new URLClassLoader(new URL[] {artifact.toUri().toURL()},
        ClassLoader.getPlatformClassLoader())) {
      assertArrayEquals(actual, ShadedCodecTest.packets(isolated, fixture));
    }
    byte[] reference;
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(
        CodecFixtureTest.resource(fixture + ".reference.s16le.gz")))) {
      reference = gzip.readAllBytes();
    }
    assertEquals(54 * 2048 * 2 * 2, actual.length);
    assertEquals(reference.length, actual.length);
    var pcm = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    var ref = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    for (int sample = 0; sample < pcm.remaining(); sample++) {
      assertTrue(Math.abs(pcm.get(sample) - ref.get(sample)) <= 1,
          "Independent HE-AAC reference mismatch at sample " + sample);
    }
  }
}
