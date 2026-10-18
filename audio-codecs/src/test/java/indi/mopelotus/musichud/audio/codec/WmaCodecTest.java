package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.internal.wma.WmaPcmDecoder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WmaCodecTest {
  @ParameterizedTest
  @ValueSource(strings = {"noise-v1", "stereo-v2"})
  void comparesCompleteF32ReferenceWithShortReadsAndDifferentPcmChunks(String name)
      throws Exception {
    byte[] encoded = resource(name + ".wma");
    byte[] expected = resource(name + ".f32");
    byte[] first = null;
    for (int request : new int[] {1, 113, 4096}) {
      CountedInput input = new CountedInput(encoded, 3);
      try (var decoder = new WmaPcmDecoder(input)) {
        int channels = name.equals("noise-v1") ? 1 : 2;
        assertEquals(name.equals("noise-v1") ? 16000 : 32000, decoder.format().sampleRate());
        assertEquals(channels, decoder.format().channels());
        assertEquals(PcmFormat.Encoding.F32LE, decoder.format().encoding());
        var output = new ByteArrayOutputStream();
        byte[] b = new byte[request * decoder.format().frameBytes() + 9];
        assertEquals(0, decoder.readFrames(b, 3, 0));
        assertEquals(0, decoder.framePosition());
        long frames = 0;
        int n;
        while ((n = decoder.readFrames(b, 3, request)) != -1) {
          assertTrue(n > 0 && n <= request);
          output.write(b, 3, n * decoder.format().frameBytes());
          frames += n;
          assertEquals(frames, decoder.framePosition());
        }
        assertEquals(expected.length / decoder.format().frameBytes(), frames);
        assertEquals(-1, decoder.readFrames(b, 3, request));
        byte[] actual = output.toByteArray();
        if (first == null) first = actual;
        else assertArrayEquals(first, actual, "Output must be independent of chunk sizes");
        compareCompleteReference(expected, actual);
        decoder.close();
        decoder.cancel();
        assertThrows(IOException.class, () -> decoder.readFrames(b, 0, 1));
      }
      assertEquals(1, input.closes.get());
    }
  }

  @Test
  void invalidConstructionClosesOwnedInputAndPreservesCloseError() {
    for (boolean failClose : new boolean[] {false, true}) {
      var input =
          new CountedInput(new byte[3], 1) {
            @Override
            public void close() throws IOException {
              super.close();
              if (failClose) throw new IOException("close-marker");
            }
          };
      var error = assertThrows(EOFException.class, () -> new WmaPcmDecoder(input));
      assertEquals(1, input.closes.get());
      assertEquals(failClose ? 1 : 0, error.getSuppressed().length);
      if (failClose) assertEquals("close-marker", error.getSuppressed()[0].getMessage());
    }
  }

  @Test
  void malformedAndTruncatedPacketsFailTerminally() throws Exception {
    byte[] encoded = resource("noise-v1.wma");
    try (var decoder = new WmaPcmDecoder(new ByteArrayInputStream(Arrays.copyOf(encoded, 600)))) {
      byte[] b = new byte[4096];
      assertThrows(
          IOException.class,
          () -> {
            while (decoder.readFrames(b, 0, 1024) != -1) {}
          });
      assertThrows(IOException.class, () -> decoder.readFrames(b, 0, 1024));
    }
    byte[] invalid = encoded.clone();
    Arrays.fill(invalid, 16, 24, (byte) 0x7f);
    var input = new CountedInput(invalid, 7);
    assertThrows(IOException.class, () -> new WmaPcmDecoder(input));
    assertEquals(1, input.closes.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"pro", "encrypted", "spread"})
  void unsupportedAsfFeaturesAreRejected(String feature) throws Exception {
    byte[] encoded = resource("noise-v1.wma");
    byte[] streamGuid = HexFormat.of().parseHex("9107dcb7b7a9cf118ee600c00c205365");
    int stream = -1;
    for (int p = 0; p <= encoded.length - streamGuid.length; p++) {
      if (Arrays.equals(encoded, p, p + streamGuid.length, streamGuid, 0, streamGuid.length)) {
        stream = p;
        break;
      }
    }
    assertTrue(stream >= 0);
    if (feature.equals("pro")) encoded[stream + 78] = 0x62;
    else if (feature.equals("encrypted")) encoded[stream + 73] |= (byte) 0x80;
    else {
      int typeSize = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(stream + 64);
      encoded[stream + 78 + typeSize] = 2;
    }
    var input = new CountedInput(encoded, 11);
    assertThrows(IOException.class, () -> new WmaPcmDecoder(input));
    assertEquals(1, input.closes.get());
  }

  @Test
  void concurrentCloseClosesInputOnce() throws Exception {
    var input = new CountedInput(resource("noise-v1.wma"), 29);
    var decoder = new WmaPcmDecoder(input);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 64; i++)
        tasks.add(
            pool.submit(
                () -> {
                  try {
                    decoder.close();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                }));
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, input.closes.get());
  }

  @Test
  void closeCancelsBlockedPacketReadWhenInputSupportsCancellation() throws Exception {
    byte[] file = resource("noise-v1.wma");
    byte[] guid = HexFormat.of().parseHex("3626b2758e66cf11a6d900aa0062ce6c");
    int headerEnd = -1;
    for (int p = 0; p <= file.length - guid.length; p++) {
      if (Arrays.equals(file, p, p + guid.length, guid, 0, guid.length)) {
        headerEnd = p + 50;
        break;
      }
    }
    assertTrue(headerEnd > 0);
    int end = headerEnd;
    CountDownLatch blocked = new CountDownLatch(1), released = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();
    InputStream input =
        new InputStream() {
          int position;

          @Override
          public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 255;
          }

          @Override
          public int read(byte[] b, int offset, int len) throws IOException {
            if (position < end) {
              int n = Math.min(len, end - position);
              System.arraycopy(file, position, b, offset, n);
              position += n;
              return n;
            }
            blocked.countDown();
            try {
              if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("Read timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            throw new IOException("Input closed");
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            released.countDown();
          }
        };
    var decoder = new WmaPcmDecoder(input);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var task =
          pool.submit(
              () -> assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1)));
      try {
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
      } finally {
        decoder.close();
      }
      task.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, closes.get());
  }

  private static void compareCompleteReference(byte[] expected, byte[] actual) {
    assertEquals(expected.length, actual.length, "Complete output, including final overlap");
    var reference = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
    var decoded = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    double power = 0, error = 0;
    while (reference.hasRemaining()) {
      float a = reference.getFloat(), b = decoded.getFloat();
      assertTrue(Float.isFinite(b));
      assertEquals(a, b, 5e-7, "Every output sample at the same absolute position");
      power += (double) a * a;
      error += (double) (a - b) * (a - b);
    }
    assertTrue(error <= power * 1e-12, "Whole-clip SNR must be at least 120 dB");
  }

  private static byte[] resource(String name) throws IOException {
    try (var in = WmaCodecTest.class.getResourceAsStream("/codecs/wma/" + name)) {
      assertNotNull(in, name);
      return in.readAllBytes();
    }
  }

  private static class CountedInput extends FilterInputStream {
    final AtomicInteger closes = new AtomicInteger();
    final int maxRead;

    CountedInput(byte[] data, int maxRead) {
      super(new ByteArrayInputStream(data));
      this.maxRead = maxRead;
    }

    @Override
    public int read(byte[] b, int offset, int len) throws IOException {
      return super.read(b, offset, Math.min(len, maxRead));
    }

    @Override
    public void close() throws IOException {
      closes.incrementAndGet();
      super.close();
    }
  }
}
