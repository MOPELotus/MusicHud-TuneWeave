package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.PresentationPlan;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.PresentedDecoder;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.SpeakerOrder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JavaStreamDecoderTest {
  private static final PcmFormat STEREO =
      new PcmFormat(48000, 2, PcmFormat.Encoding.S16LE, PcmFormat.Layout.STEREO);

  private static PresentationPlan plan(long frames) {
    return new PresentationPlan(List.of(new PresentationPlan.Segment(0, frames)), frames);
  }

  private static final class Samples implements PcmDecoder {
    long position;
    volatile boolean closed;

    public PcmFormat format() {
      return STEREO;
    }

    public long framePosition() {
      return position;
    }

    public int readFrames(byte[] output, int offset, int wanted) throws IOException {
      if (closed) throw new IOException("Closed sample source");
      int count = (int) Math.min(wanted, 48000 - position);
      if (count == 0) return -1;
      var bytes = ByteBuffer.wrap(output, offset, count * 4).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < count; i++) {
        short sample = (short) (position++ * 17);
        bytes.putShort(sample).putShort((short) -sample);
      }
      return count;
    }

    public void close() {
      closed = true;
    }
  }

  private static PresentedDecoder.Factory factory() {
    return () ->
        new PresentedDecoder.Attempt() {
          final Samples samples = new Samples();

          public PcmDecoder open() {
            return samples;
          }

          public void cancel() {
            samples.close();
          }
        };
  }

  @Test
  void seekUsesDeliveredFramesAndCloseOwnsTheWholeResource() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    var presented = new PresentedDecoder(factory(), plan(48000));
    try (var decoder =
        new JavaStreamDecoder(
            presented, false, false, SpeakerOrder.forFormat(STEREO), closed::incrementAndGet)) {
      assertTrue(decoder.seekToMillis(123));
      assertEquals(123, decoder.getPositionMillis());
      byte[] bytes = decoder.readChunk(4);
      assertEquals(
          (short) (5904 * 17), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort());
      assertEquals(123, decoder.getPositionMillis());
      assertTrue(decoder.seekToMillis(0));
      assertArrayEquals(new byte[4], decoder.readChunk(4));
      assertThrows(IllegalArgumentException.class, () -> decoder.readChunk(1));
      decoder.close();
      decoder.close();
      assertNull(decoder.readChunk(4));
      assertFalse(decoder.seekToMillis(0));
    }
    assertEquals(1, closed.get());
  }

  @Test
  void lateOldSeekNeverReplacesTheNewClockOrLeaksItsDecoder() throws Exception {
    AtomicInteger opened = new AtomicInteger();
    CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
    var samples = new CopyOnWriteArrayList<Samples>();
    PresentedDecoder.Factory factory =
        () ->
            new PresentedDecoder.Attempt() {
              final int index = opened.incrementAndGet();
              final Samples source = new Samples();

              {
                samples.add(source);
              }

              public PcmDecoder open() throws IOException {
                if (index == 2) {
                  slowStarted.countDown();
                  try {
                    releaseSlow.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                  }
                }
                return source;
              }

              public void cancel() {
                source.close();
              }
            };
    var executor = Executors.newFixedThreadPool(2);
    var decoder = new PresentedDecoder(factory, plan(48000));
    try {
      Future<?> old =
          executor.submit(() -> assertThrows(IOException.class, () -> decoder.seekFrame(40000)));
      assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
      try {
        assertTrue(decoder.seekFrame(1234));
      } finally {
        releaseSlow.countDown();
      }
      old.get(2, TimeUnit.SECONDS);
      assertEquals(1234, decoder.framePosition());
      assertTrue(samples.get(1).closed);
      decoder.close();
      assertTrue(samples.stream().allMatch(s -> s.closed));
    } finally {
      releaseSlow.countDown();
      decoder.close();
      executor.shutdownNow();
    }
  }
}
