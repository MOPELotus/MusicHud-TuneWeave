package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackBufferTest {
    @Test void replacementUnblocksProducerAndRejectsOldCompletionAndRecoveredQueue() throws Exception {
        var pcm = new PcmPlaybackBuffer(1); var old = pcm.reset(1);
        var chunk = new PcmPlaybackBuffer.Chunk(new byte[16], 0x1103, 8000, 0);
        pcm.offer(old, chunk);
        CountDownLatch entered = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> waiting = executor.submit(() -> { entered.countDown(); return pcm.offer(old, chunk); });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var replacement = pcm.reset(2);
            assertFalse(waiting.get(1, TimeUnit.SECONDS));
            pcm.offer(replacement, chunk);
            pcm.finish(old, new IllegalStateException("late decoder"));
            pcm.recover(old);
            assertFalse(pcm.finished(replacement));
            assertNull(pcm.failure(replacement));
            assertEquals(chunk, pcm.peek(replacement));
        }
    }

    @Test void byteAlignmentAndWallClockBoundariesDoNotSplitOrOverflowFrames() {
        for (int channels : new int[]{4, 6, 7, 8}) {
            int format = OpenAlFormatSelector.select(channels, true);
            int frame = channels * 4;
            assertThrows(IllegalArgumentException.class, () -> new PcmPlaybackBuffer.Chunk(new byte[frame - 1], format, 44100, 0));
            var chunk = new PcmPlaybackBuffer.Chunk(new byte[frame * 100], format, 44100, 0);
            var trimmed = chunk.trimBefore(1);
            assertEquals(44, trimmed.startFrame());
            assertEquals(frame * 56, trimmed.bytes().length);
            assertSame(chunk, chunk.trimBefore(-1));
            assertNull(chunk.trimBefore(Long.MAX_VALUE));
        }
        assertEquals(Long.MAX_VALUE, PcmPlaybackBuffer.frameAt(Long.MAX_VALUE, 384000));
    }

    @Test void claimRecoveryKeepsFailedUploadBeforeLaterPrefetchedData() throws Exception {
        var pcm = new PcmPlaybackBuffer(4); var token = pcm.reset(7);
        for (int frame = 0; frame < 3; frame++) pcm.offer(token,
                new PcmPlaybackBuffer.Chunk(new byte[4], 0x1103, 8000, frame));
        pcm.claim(token, 10); pcm.claim(token, 11);
        pcm.processed(token, 10); pcm.recover(token);
        assertEquals(1, pcm.claim(token, 20).startFrame());
        assertEquals(2, pcm.claim(token, 21).startFrame());
        assertNull(pcm.peek(token));
    }
}
