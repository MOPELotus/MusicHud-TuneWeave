package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresentedDecoderSkipTest {
  private static final PcmFormat MONO =
      new PcmFormat(48000, 1, PcmFormat.Encoding.S16LE, PcmFormat.Layout.MONO);

  private static final class Samples implements PcmDecoder {
    long position;
    long end = Long.MAX_VALUE;
    boolean noProgress;
    final List<Integer> requests;

    Samples(boolean recordRequests) {
      requests = recordRequests ? new ArrayList<>() : null;
    }

    public PcmFormat format() { return MONO; }
    public long framePosition() { return position; }
    public int readFrames(byte[] target, int offset, int maxFrames) {
      if (requests != null) requests.add(maxFrames);
      if (noProgress) return 0;
      if (position >= end) return -1;
      int count = (int) Math.min(maxFrames, end - position);
      for (int i = 0; i < count; i++) {
        int value = (int) position++;
        target[offset++] = (byte) value;
        target[offset++] = (byte) (value >>> 8);
      }
      return count;
    }
    public void close() {}
  }

  private static PresentedDecoder edited(Samples samples, long firstFrame) throws IOException {
    return new PresentedDecoder(
        () -> new PresentedDecoder.Attempt() {
          public PcmDecoder open() { return samples; }
          public void cancel() { samples.close(); }
        },
        new PresentationPlan(
            List.of(new PresentationPlan.Segment(firstFrame, Long.MAX_VALUE)), Long.MAX_VALUE));
  }

  @Test
  void alignedSequentialReadsDoNotAllocateDiscardBuffers() throws Exception {
    var bean = ManagementFactory.getThreadMXBean();
    assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
    var allocations = (com.sun.management.ThreadMXBean) bean;
    assumeTrue(allocations.isThreadAllocatedMemorySupported());
    allocations.setThreadAllocatedMemoryEnabled(true);
    var samples = new Samples(false);
    try (var decoder = edited(samples, 0)) {
      byte[] frame = new byte[2];
      for (int i = 0; i < 8192; i++) decoder.readFrames(frame, 0, 1);
      long thread = Thread.currentThread().threadId();
      long before = allocations.getThreadAllocatedBytes(thread);
      for (int i = 0; i < 4096; i++) decoder.readFrames(frame, 0, 1);
      long allocated = allocations.getThreadAllocatedBytes(thread) - before;
      assertEquals(12288, decoder.framePosition());
      assertTrue(allocated < 4 * 1024 * 1024,
          "Aligned small reads allocated discard scratch: " + allocated);
    }
  }

  @Test
  void nonzeroEditStillDiscardsInBoundedReadsBeforeDeliveringExactFrame() throws Exception {
    var samples = new Samples(true);
    try (var decoder = edited(samples, 5001)) {
      byte[] frame = new byte[2];
      assertEquals(1, decoder.readFrames(frame, 0, 1));
      assertEquals(5001, (frame[0] & 255) | (frame[1] & 255) << 8);
      assertEquals(List.of(4096, 905, 1), samples.requests);
      assertEquals(1, decoder.framePosition());
      assertEquals(5002, samples.framePosition());
    }
  }

  @Test
  void prematureEofDuringRequiredDiscardRemainsAnError() throws Exception {
    var samples = new Samples(false);
    samples.end = 4096;
    try (var decoder = edited(samples, 5001)) {
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1));
      assertEquals(0, decoder.framePosition());
    }
  }

  @Test
  void zeroProgressDuringRequiredDiscardRemainsAnError() throws Exception {
    var samples = new Samples(false);
    samples.noProgress = true;
    try (var decoder = edited(samples, 1)) {
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1));
      assertEquals(0, decoder.framePosition());
    }
  }
}
