package indi.mopelotus.musichud.client.audio.decoder;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LavaplayerStreamDecoderTest {
    private static final byte[] PCM = {1, 2, 3, 4};

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endMarkerBeforeFailureMustNotBecomeSuccessfulEof(boolean emitPcm) throws Exception {
        GatedTrack track = new GatedTrack(emitPcm, true);
        try (var decoder = LavaplayerStreamDecoder.start(new DefaultAudioPlayerManager(), track);
             var reader = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(track.markerPublished.await(2, TimeUnit.SECONDS));
                var result = reader.submit(() -> assertThrows(RuntimeException.class, () -> decoder.readChunk(8)));
                assertTrue(track.markerRead.await(2, TimeUnit.SECONDS));
                // Lavaplayer 2.2.7 can publish its terminator before logging and delivering the exception.
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                track.finish.countDown();
                assertNotNull(result.get(2, TimeUnit.SECONDS).getCause());
            } finally {
                track.finish.countDown();
            }
        }
    }

    @Test
    void successfulCompletionStillReturnsFinalPcmThenEof() throws Exception {
        GatedTrack track = new GatedTrack(true, false);
        track.finish.countDown();
        try (var decoder = LavaplayerStreamDecoder.start(new DefaultAudioPlayerManager(), track)) {
            assertArrayEquals(PCM, decoder.readChunk(8));
            assertNull(decoder.readChunk(8));
        }
    }

    @Test
    void closeReleasesReaderWaitingForLateCompletion() throws Exception {
        GatedTrack track = new GatedTrack(false, false);
        try (var decoder = LavaplayerStreamDecoder.start(new DefaultAudioPlayerManager(), track);
             var reader = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(track.markerPublished.await(2, TimeUnit.SECONDS));
                var result = reader.submit(() -> decoder.readChunk(8));
                assertTrue(track.markerRead.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                decoder.close();
                assertNull(result.get(2, TimeUnit.SECONDS));
            } finally {
                track.finish.countDown();
            }
        }
    }

    @Test
    void interruptionReleasesReaderAndPreservesInterruptFlag() throws Exception {
        GatedTrack track = new GatedTrack(false, false);
        try (var decoder = LavaplayerStreamDecoder.start(new DefaultAudioPlayerManager(), track)) {
            var result = new CompletableFuture<Boolean>();
            var reader = new Thread(() -> {
                try {
                    result.complete(decoder.readChunk(8) == null && Thread.currentThread().isInterrupted());
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            try {
                reader.start();
                assertTrue(track.markerRead.await(2, TimeUnit.SECONDS));
                reader.interrupt();
                assertTrue(result.get(2, TimeUnit.SECONDS));
            } finally {
                track.finish.countDown();
                reader.interrupt();
                reader.join(2000);
            }
        }
    }

    private static final class GatedTrack extends BaseAudioTrack {
        final CountDownLatch markerPublished = new CountDownLatch(1);
        final CountDownLatch markerRead = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final boolean emitPcm;
        final boolean fail;

        GatedTrack(boolean emitPcm, boolean fail) {
            super(new AudioTrackInfo("fixture", "test", 1000, "fixture", false, null));
            this.emitPcm = emitPcm;
            this.fail = fail;
        }

        @Override
        public AudioFrame provide(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
            AudioFrame frame = super.provide(timeout, unit);
            if (frame != null && frame.isTerminator()) markerRead.countDown();
            return frame;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) throws Exception {
            if (emitPcm) executor.getAudioBuffer().consume(new ImmutableAudioFrame(
                    0, PCM, 100, StandardAudioDataFormats.COMMON_PCM_S16_LE));
            executor.getAudioBuffer().setTerminateOnEmpty();
            markerPublished.countDown();
            // Model the upstream logging/callback window, which cannot be cancelled by stopTrack().
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (finish.getCount() != 0) {
                if (System.nanoTime() - deadline >= 0) throw new IOException("Fixture completion timed out");
                try { finish.await(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* completion callback is still pending */ }
            }
            if (fail) throw new IOException("Fixture decoder failed after publishing its end marker");
        }
    }
}
