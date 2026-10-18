package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Mp3TimingTest {
  @Test
  void embeddedAndContainerTimingAreExplicitAndIndependent() throws Exception {
    byte[] file = CodecFixtureTest.resource("mp3.mp3");
    try (var embedded = JavaCodecs.mp3(new ByteArrayInputStream(file));
        var media = JavaCodecs.mp3RawMedia(new ByteArrayInputStream(file))) {
      assertEquals(4800, read(embedded));
      assertEquals(8064, read(media));
    }
  }

  @Test
  void freeFormatDoesNotSynchronizeToUnrelatedLaterFrames() throws Exception {
    // A valid free-format MPEG1/LayerIII/48k header followed by apparent supported payload headers.
    // The actual640kbps LAME stream is separately qualified in the independent baseline corpus.
    var bytes = new ByteArrayOutputStream();
    bytes.write(new byte[] {(byte) 0xff, (byte) 0xfb, 4, 0});
    bytes.write(CodecFixtureTest.resource("mp3.mp3"));
    var closes = new AtomicInteger();
    InputStream input =
        new ByteArrayInputStream(bytes.toByteArray()) {
          public void close() {
            closes.incrementAndGet();
          }
        };
    var error = assertThrows(IOException.class, () -> JavaCodecs.mp3(input));
    assertTrue(error.getMessage().contains("Free-format"));
    assertEquals(1, closes.get());
  }

  @Test
  void informationFrameOnlyCannotSilentlyBecomeAnEmptySong() throws Exception {
    try (var decoder =
        JavaCodecs.mp3(
            new ByteArrayInputStream(
                CodecFixtureTest.resource("mp3-boundary/info-frame-only.mp3")))) {
      assertThrows(EOFException.class, () -> decoder.readFrames(new byte[4], 0, 1));
      assertEquals(0, decoder.framePosition());
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1));
    }
  }

  private static long read(PcmDecoder decoder) throws IOException {
    byte[] b = new byte[113 * decoder.format().frameBytes()];
    long n = 0;
    int f;
    while ((f = decoder.readFrames(b, 0, 113)) != -1) n += f;
    assertEquals(n, decoder.framePosition());
    return n;
  }
}
