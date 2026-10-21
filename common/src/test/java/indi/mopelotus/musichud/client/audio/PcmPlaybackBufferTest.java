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
                new PcmPlaybackBuffer.Chunk(new byte[3200], 0x1103, 8000, frame * 800));
        pcm.claim(token, 10); pcm.claim(token, 11);
        pcm.processed(token, 10); pcm.recover(token);
        assertEquals(800, pcm.claim(token, 20).startFrame());
        assertEquals(1600, pcm.claim(token, 21).startFrame());
        assertNull(pcm.peek(token));
    }

    @Test void deviceChunksCoalesceAvailableFramesWithoutWaitingOrLosingSplitTails() throws Exception {
        for (int rate : new int[]{8000, 44100, 384000}) for (int channels : new int[]{2, 8}) {
            int format = OpenAlFormatSelector.select(channels, true), frameSize = channels * 4;
            int limit = Math.min(rate / 10, 256 * 1024 / frameSize);
            int frames = limit + 777;
            byte[] original = new byte[frames * frameSize];
            for (int i = 0; i < original.length; i++) original[i] = (byte) i;
            var pcm = new PcmPlaybackBuffer(200); var token = pcm.reset(1);
            for (int start = 0; start < frames; start += 512) {
                int end = Math.min(frames, start + 512);
                pcm.offer(token, new PcmPlaybackBuffer.Chunk(java.util.Arrays.copyOfRange(original,
                        start * frameSize, end * frameSize), format, rate, start));
            }
            var first = pcm.claim(token, 1);
            assertEquals(limit, first.frames());
            assertArrayEquals(java.util.Arrays.copyOf(original, limit * frameSize), first.bytes());
            var tail = pcm.claim(token, 2);
            assertEquals(limit, tail.startFrame());
            assertEquals(777, tail.frames());
            assertArrayEquals(java.util.Arrays.copyOfRange(original, limit * frameSize, original.length), tail.bytes());
            assertNull(pcm.peek(token));
        }
        var tiny = new PcmPlaybackBuffer(60); var token = tiny.reset(1);
        for (int i = 0; i < 16; i++) tiny.offer(token,
                new PcmPlaybackBuffer.Chunk(new byte[128 * 8], OpenAlFormatSelector.select(2, true), 44100, i * 128));
        var available = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> tiny.claim(token, 1));
        assertEquals(2048, available.frames(), "Claim uses all existing tiny chunks without waiting to fill 100ms");
        assertNull(tiny.peek(token));
    }

    @Test void coalescingDoesNotBridgeSourceGapsFormatsRatesOrDecoderEpochs() throws Exception {
        var pcm = new PcmPlaybackBuffer(8); var token = pcm.reset(1);
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[40], 0x1103, 8000, 0));
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[40], 0x1103, 8000, 20));
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[40], 0x1103, 16000, 30));
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[40], 0x1101, 16000, 40));
        assertEquals(10, pcm.claim(token, 1).frames());
        assertEquals(20, pcm.claim(token, 2).startFrame());
        assertEquals(16000, pcm.claim(token, 3).sampleRate());
        assertEquals(0x1101, pcm.claim(token, 4).format());
        var next = pcm.reset(2);
        assertNull(pcm.claim(token, 5));
        assertTrue(pcm.empty(next));
    }
}
