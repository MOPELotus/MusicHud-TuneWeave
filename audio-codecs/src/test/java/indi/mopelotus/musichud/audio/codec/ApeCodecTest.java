package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.internal.ape.ApePcmDecoder;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApeCodecTest {
  private byte[] resource(String name) throws IOException {
    try (InputStream source = getClass().getResourceAsStream("/codecs/ape/" + name)) {
      assertNotNull(source, name);
      return source.readAllBytes();
    }
  }

  @Test
  void independentEncoderVectorsPreserveSourceDepthAndEveryFrame() throws Exception {
    for (int[] variant : new int[][] {{16, 1}, {16, 2}, {24, 1}, {24, 2}, {8, 1}}) {
      String stem = "synthetic-" + variant[0] + "-" + variant[1];
      AtomicInteger closes = new AtomicInteger();
      InputStream stream =
          new ByteArrayInputStream(resource(stem + ".ape")) {
            @Override
            public synchronized int read(byte[] b, int offset, int length) {
              return super.read(b, offset, Math.min(137, length));
            }

            @Override
            public void close() {
              closes.incrementAndGet();
            }
          };
      try (var decoder = new ApePcmDecoder(stream)) {
        assertEquals(44100, decoder.format().sampleRate());
        assertEquals(variant[1], decoder.format().channels());
        assertEquals(variant[0] / 8, decoder.format().encoding().bytes);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        int frameBytes = decoder.format().frameBytes();
        byte[] buffer = new byte[83 * frameBytes + 2];
        assertEquals(0, decoder.readFrames(buffer, 1, 0));
        for (int frames; (frames = decoder.readFrames(buffer, 1, 83)) != -1; ) {
          assertTrue(frames > 0);
          actual.write(buffer, 1, frames * frameBytes);
        }
        assertEquals(2173, decoder.framePosition());
        assertArrayEquals(resource(stem + ".pcm"), actual.toByteArray(), stem);
        assertEquals(-1, decoder.readFrames(buffer, 1, 83));
      }
      assertEquals(1, closes.get());
    }
  }

  @Test
  void earlyCloseIsOwnedAndIdempotent() throws Exception {
    AtomicInteger closes = new AtomicInteger();
    InputStream stream =
        new ByteArrayInputStream(resource("synthetic-16-2.ape")) {
          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    var decoder = new ApePcmDecoder(stream);
    decoder.close();
    decoder.cancel();
    assertEquals(1, closes.get());
    assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1));
    assertEquals(0, decoder.framePosition());
  }

  @Test
  void invalidOpeningClosesOwnedInput() {
    AtomicInteger closes = new AtomicInteger();
    InputStream stream =
        new ByteArrayInputStream(new byte[10]) {
          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    assertThrows(IOException.class, () -> new ApePcmDecoder(stream));
    assertEquals(1, closes.get());
  }

  @Test
  void corruptedCrcExposesNoPcm() throws Exception {
    byte[] ape = resource("synthetic-16-2.ape");
    int descriptorBytes = littleInt(ape, 8), headerBytes = littleInt(ape, 12);
    int firstFrame = littleInt(ape, descriptorBytes + headerBytes);
    ape[firstFrame] ^= 1;
    try (var decoder = new ApePcmDecoder(new ByteArrayInputStream(ape))) {
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1));
      assertEquals(0, decoder.framePosition());
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1));
    }
  }

  private static int littleInt(byte[] bytes, int offset) {
    return (bytes[offset] & 255)
        | ((bytes[offset + 1] & 255) << 8)
        | ((bytes[offset + 2] & 255) << 16)
        | ((bytes[offset + 3] & 255) << 24);
  }

  @Test
  void closeCancelsBlockedReadWithoutReturningLatePcm() throws Exception {
    byte[] ape = resource("synthetic-16-2.ape");
    CountDownLatch reading = new CountDownLatch(1), released = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();
    class BlockingInput extends ByteArrayInputStream {
      volatile boolean block;

      BlockingInput() {
        super(ape);
      }

      @Override
      public int read(byte[] b, int offset, int count) {
        if (block) {
          reading.countDown();
          try {
            if (!released.await(5, TimeUnit.SECONDS))
              throw new AssertionError("Read not cancelled");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        }
        return super.read(b, offset, count);
      }

      @Override
      public void close() {
        closes.incrementAndGet();
        released.countDown();
      }
    }
    BlockingInput input = new BlockingInput();
    var decoder = new ApePcmDecoder(input);
    input.block = true;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> result = executor.submit(() -> decoder.readFrames(new byte[4], 0, 1));
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      decoder.close();
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(0, decoder.framePosition());
      assertEquals(1, closes.get());
    } finally {
      decoder.close();
      released.countDown();
      executor.shutdownNow();
    }
  }
}
