package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OggLinksTest {
  record Pair(byte[] first, byte[] second) {
    byte[] physical() {
      return concat(first, second);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"same", "mixed", "rate", "channels"})
  void preservesEveryNativeLinkAndDiscoversIncrementally(String mode) throws Exception {
    Pair pair = pair(mode);
    byte[][] expected = {single(pair.first), single(pair.second)};
    String[] formats = {format(pair.first), format(pair.second)};
    Counted input = new Counted(pair.physical(), 7);
    try (var links = JavaCodecs.oggLinks(input)) {
      for (int i = 0; i < 2; i++) {
        var link = links.nextLink();
        assertNotNull(link);
        assertEquals(i, link.number());
        assertEquals(i, link.group());
        byte[] encoded = i == 0 ? pair.first : pair.second;
        assertEquals(
            ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(14), link.serial());
        assertEquals(i == 0 ? 0 : pair.first.length, link.startOffset());
        assertEquals(formats[i], link.format().toString());
        assertEquals(0, link.framePosition());
        if (i == 0)
          assertTrue(
              input.delivered <= pair.first.length,
              "No discovery of the next group before first PCM");
        byte[] pcm = OggSeekTest.read(link);
        assertArrayEquals(expected[i], pcm);
        assertEquals(pcm.length / link.format().frameBytes(), link.framePosition());
        assertEquals(i == 0 ? pair.first.length : pair.physical().length, link.endOffset());
        assertEquals(0, input.closes.get(), "Logical EOF never closes the physical source");
      }
      assertNull(links.nextLink());
      assertNull(links.nextLink());
    }
    assertEquals(1, input.closes.get());
  }

  @Test
  void partialConsumptionAndEarlyLinkCloseRequireExplicitDiscard() throws Exception {
    Pair pair = pair("mixed");
    Counted input = new Counted(pair.physical(), 9);
    try (var links = JavaCodecs.oggLinks(input)) {
      var first = links.nextLink();
      assertEquals(1, first.readFrames(new byte[first.format().frameBytes()], 0, 1));
      assertThrows(IOException.class, links::nextLink);
      assertEquals(0, input.closes.get(), "Precondition rejection is recoverable");
      first.close();
      assertEquals(0, input.closes.get());
      assertThrows(
          IOException.class, links::nextLink, "Closing a codec does not authorize dropping PCM");
      links.discardCurrentLink();
      assertEquals(pair.first.length, first.endOffset());
      var second = links.nextLink();
      assertEquals(1, second.number());
      assertArrayEquals(single(pair.second), OggSeekTest.read(second));
      assertNull(links.nextLink());
    }
    assertEquals(1, input.closes.get());
  }

  @Test
  void unknownEndOffsetBecomesKnownOnlyWhenDiscardFindsEos() throws Exception {
    Pair pair = pair("same");
    Counted input = new Counted(pair.physical(), 13);
    try (var links = new OggLinks(input, logical -> new Stub(logical))) {
      var first = links.nextLink();
      assertEquals(-1, first.endOffset());
      assertTrue(input.delivered < pair.first.length);
      links.discardCurrentLink();
      assertEquals(pair.first.length, first.endOffset());
      assertNotNull(links.nextLink());
    }
    assertEquals(1, input.closes.get());
  }

  @Test
  void cancelledBlockedReadRejectsLatePhysicalDataAndPcm() throws Exception {
    byte[] encoded = pair("same").first;
    int firstPage = pageSize(encoded, 0);
    Gate input = new Gate(encoded, firstPage);
    AtomicInteger codecCloses = new AtomicInteger();
    var links =
        new OggLinks(
            input,
            logical -> {
              assertEquals(firstPage, logical.readNBytes(firstPage).length);
              return new Stub(logical) {
                @Override
                public int readFrames(byte[] b, int offset, int frames) throws IOException {
                  logical.readAllBytes();
                  b[offset] = 1;
                  return 1;
                }

                @Override
                public void close() throws IOException {
                  super.close();
                  codecCloses.incrementAndGet();
                }
              };
            });
    var link = links.nextLink();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          pool.submit(
              () -> assertThrows(IOException.class, () -> link.readFrames(new byte[2], 0, 1)));
      try {
        assertTrue(input.blocked.await(5, TimeUnit.SECONDS));
      } finally {
        links.close();
      }
      read.get(5, TimeUnit.SECONDS);
    } finally {
      input.release.countDown();
      links.close();
    }
    assertTrue(
        input.lateBytes.get() > 0, "The test source deliberately returned bytes after close");
    assertEquals(0, link.framePosition());
    assertEquals(1, input.closes.get());
    assertEquals(1, codecCloses.get());
    assertThrows(IOException.class, links::nextLink);
  }

  @Test
  void cancellationRejectsLateFactoryPublicationAndClosesAllocatedCodec() throws Exception {
    byte[] encoded = pair("same").first;
    CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
    Counted physical =
        new Counted(encoded, 7) {
          @Override
          public void close() {
            super.close();
            closed.countDown();
          }
        };
    AtomicInteger codecCloses = new AtomicInteger();
    var links =
        new OggLinks(
            physical,
            logical -> {
              entered.countDown();
              await(closed);
              return new Stub(logical) {
                @Override
                public void close() throws IOException {
                  super.close();
                  codecCloses.incrementAndGet();
                }
              };
            });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var opening = pool.submit(() -> assertThrows(IOException.class, links::nextLink));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
      } finally {
        links.close();
      }
      opening.get(5, TimeUnit.SECONDS);
    } finally {
      closed.countDown();
      links.close();
    }
    assertEquals(1, physical.closes.get());
    assertEquals(1, codecCloses.get());
  }

  @Test
  void malformedPageFailsTerminallyAndClosesPhysicalInput() throws Exception {
    byte[] encoded = pair("same").first.clone();
    encoded[encoded.length - 1] ^= 1;
    Counted input = new Counted(encoded, 7);
    try (var links = JavaCodecs.oggLinks(input)) {
      assertThrows(
          IOException.class,
          () -> {
            var link = links.nextLink();
            OggSeekTest.read(link);
          });
      assertThrows(IOException.class, links::nextLink);
    }
    assertEquals(1, input.closes.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"same", "mixed", "rate", "channels"})
  void actualShadedArtifactRetainsChainApiAndEveryLinkPcm(String mode) throws Exception {
    Pair pair = pair(mode);
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var factory = loader.loadClass(prefix + "JavaCodecs");
      var chainType = loader.loadClass(prefix + "OggLinks");
      var linkType = loader.loadClass(prefix + "OggLinks$Link");
      var pcmType = loader.loadClass(prefix + "PcmDecoder");
      Counted input = new Counted(pair.physical(), 7);
      Object links = factory.getMethod("oggLinks", InputStream.class).invoke(null, input);
      try {
        for (int i = 0; i < 2; i++) {
          Object link = chainType.getMethod("nextLink").invoke(links);
          assertNotNull(link);
          assertEquals(i, linkType.getMethod("number").invoke(link));
          Object format = pcmType.getMethod("format").invoke(link);
          assertEquals(format(i == 0 ? pair.first : pair.second), format.toString());
          int frameBytes = (int) format.getClass().getMethod("frameBytes").invoke(format);
          var out = new ByteArrayOutputStream();
          byte[] b = new byte[113 * frameBytes];
          int n;
          while ((n =
                  (int)
                      pcmType
                          .getMethod("readFrames", byte[].class, int.class, int.class)
                          .invoke(link, b, 0, 113))
              != -1) out.write(b, 0, n * frameBytes);
          assertArrayEquals(single(i == 0 ? pair.first : pair.second), out.toByteArray());
          assertEquals(
              (long) out.size() / frameBytes, pcmType.getMethod("framePosition").invoke(link));
        }
        assertNull(chainType.getMethod("nextLink").invoke(links));
      } finally {
        chainType.getMethod("close").invoke(links);
      }
      assertEquals(1, input.closes.get());
    }
  }

  static Pair pair(String mode) throws IOException {
    byte[] first = CodecFixtureTest.resource(mode.equals("channels") ? "opus.ogg" : "vorbis.ogg");
    byte[] second =
        CodecFixtureTest.resource(
            mode.equals("mixed") || mode.equals("channels") ? "opus.ogg" : "vorbis.ogg");
    int serial = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN).getInt(14) ^ 0x5a3612fe;
    for (int p = 0; p < second.length; p += pageSize(second, p)) {
      ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN).putInt(p + 14, serial);
      if (p == 0 && mode.equals("rate")) {
        int body = p + 27 + (second[p + 26] & 255);
        ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN).putInt(body + 12, 24000);
      }
      if (p == 0 && mode.equals("channels")) {
        int body = p + 27 + (second[p + 26] & 255);
        second[body + 9] = 1; // A valid Opus mono output mapping can decode stereo-coded packets.
      }
      crc(second, p);
    }
    return new Pair(first, second);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] result = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }

  static int pageSize(byte[] b, int offset) {
    int count = b[offset + 26] & 255, n = 27 + count;
    for (int i = 0; i < count; i++) n += b[offset + 27 + i] & 255;
    return n;
  }

  static void crc(byte[] b, int offset) {
    int value = 0, size = pageSize(b, offset);
    for (int i = 0; i < size; i++) {
      int x = i >= 22 && i < 26 ? 0 : b[offset + i] & 255;
      value ^= x << 24;
      for (int bit = 0; bit < 8; bit++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
    }
    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 22, value);
  }

  static byte[] single(byte[] encoded) throws IOException {
    try (var decoder = JavaCodecs.ogg(new ByteArrayInputStream(encoded))) {
      return OggSeekTest.read(decoder);
    }
  }

  static String format(byte[] encoded) throws IOException {
    try (var decoder = JavaCodecs.ogg(new ByteArrayInputStream(encoded))) {
      return decoder.format().toString();
    }
  }

  static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

  static class Counted extends ByteArrayInputStream {
    final AtomicInteger closes = new AtomicInteger();
    final int maxRead;
    int delivered;

    Counted(byte[] b, int maxRead) {
      super(b);
      this.maxRead = maxRead;
    }

    @Override
    public synchronized int read() {
      int n = super.read();
      if (n >= 0) delivered++;
      return n;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      int n = super.read(b, off, Math.min(maxRead, len));
      if (n > 0) delivered += n;
      return n;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  static class Stub implements PcmDecoder {
    final InputStream logical;

    Stub(InputStream logical) {
      this.logical = logical;
    }

    public PcmFormat format() {
      return new PcmFormat(48000, 1, PcmFormat.Encoding.S16LE, PcmFormat.Layout.MONO);
    }

    public long framePosition() {
      return 0;
    }

    public int readFrames(byte[] b, int offset, int frames) throws IOException {
      logical.readAllBytes();
      return -1;
    }

    public void close() throws IOException {
      logical.close();
    }
  }

  static class Gate extends InputStream {
    final byte[] bytes;
    final int gate;
    int position;
    final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger closes = new AtomicInteger(), lateBytes = new AtomicInteger();

    Gate(byte[] b, int gate) {
      bytes = b;
      this.gate = gate;
    }

    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      int n = read(b, 0, 1);
      return n < 0 ? -1 : b[0] & 255;
    }

    @Override
    public int read(byte[] b, int offset, int len) throws IOException {
      if (position >= gate && closes.get() == 0) {
        blocked.countDown();
        await(release);
      }
      if (position == bytes.length) return -1;
      int n = Math.min(len, bytes.length - position);
      if (closes.get() == 0) n = Math.min(n, gate - position);
      else lateBytes.addAndGet(n);
      System.arraycopy(bytes, position, b, offset, n);
      position += n;
      return n;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
      release.countDown();
    }
  }
}
