package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackStartupTest {
    private static PcmPlaybackBuffer.Chunk chunk(long start, int frames, int rate) {
        var bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long i = start; i < start + frames; i++) bytes.putFloat(i + 1).putFloat(-i - 1);
        return new PcmPlaybackBuffer.Chunk(bytes.array(), OpenAlFormatSelector.AL_FORMAT_STEREO_FLOAT32, rate, start);
    }

    @Test void tinyFirstFragmentWaitsAndStartsOnceFromFrameZeroWithEnoughPcmForTheNextTick() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        var driver = new PcmPlaybackChunkTimingTest.ClockedDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            pcm.offer(token, chunk(0, 128, 44100));
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(0, AudioOutputMode.STEREO, 1));
            assertEquals(0, driver.creations); assertEquals(0, driver.playCalls);
            // The decoder resumes forty milliseconds later. Local playback's
            // clock remains at zero until actual output, so its head survives.
            pcm.offer(token, chunk(128, 44100, 44100));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.STEREO, 1));
            assertEquals(1f, ByteBuffer.wrap(driver.firstPlayed.getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN).getFloat());
            driver.advance(1764);
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(40, AudioOutputMode.STEREO, 1));
            assertEquals(0, driver.underruns); assertEquals(1, driver.creations); assertEquals(1, driver.playCalls);
            assertEquals(token, pcm.token());
        }
    }

    @Test void shortNaturalEofStartsImmediatelyAndEmptyEofCompletesWithoutADevice() throws Exception {
        for (int frames : new int[]{0, 1, 128}) {
            var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
            if (frames > 0) pcm.offer(token, chunk(0, frames, 44100));
            pcm.finish(token, null);
            var driver = new PcmPlaybackControllerTest.FakeDriver();
            try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
                assertEquals(frames == 0 ? PcmPlaybackController.Result.COMPLETED : PcmPlaybackController.Result.PLAYING,
                        controller.tick(0, AudioOutputMode.STEREO, 1));
                assertEquals(frames == 0 ? 0 : 1, driver.playCalls);
            }
        }
    }

    @Test void publicClockPastTheCompleteTailFinishesImmediatelyWithoutStartingExpiredAudio() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        pcm.offer(token, chunk(0, 128, 44100)); pcm.finish(token, null);
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(1000, AudioOutputMode.STEREO, 1));
            assertEquals(0, driver.creations); assertEquals(0, driver.playCalls);
        }
    }

    @Test void fullChunkCapacityReleasesBackpressureEvenWhenHighRateTinyChunksCannotReachTheTarget() throws Exception {
        for (int capacity : new int[]{1, 2, 60}) {
            var pcm = new PcmPlaybackBuffer(capacity); var token = pcm.reset(1);
            for (int i = 0; i < capacity; i++) pcm.offer(token, chunk(i * 128L, 128, 384000));
            assertTrue(pcm.readyToStart(token));
            var driver = new PcmPlaybackControllerTest.FakeDriver();
            try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.STEREO, 1));
                // This bounded-capacity escape promises progress, not 100ms of
                // audio when the entire queue physically contains less.
                assertTrue(pcm.offer(token, chunk(capacity * 128L, 128, 384000)));
            }
        }
    }

    @Test void publicClockTrimsAStalledInitialHeadBeforeAdmissionRatherThanStagingItOnTheDevice() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        pcm.offer(token, chunk(0, 128, 44100));
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(0, AudioOutputMode.STEREO, 1));
            pcm.offer(token, chunk(44100, 8820, 44100));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(1500, AudioOutputMode.STEREO, 1));
            assertEquals(44101f, ByteBuffer.wrap(driver.firstPlayed.getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN).getFloat());
            assertEquals(1, driver.creations);
        }
    }

    @Test void ordinaryPublicStartupKeepsItsExistingClockAndBoundsOneTickOfPrebufferLag() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        pcm.offer(token, chunk(44100, 128, 44100));
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(1000, AudioOutputMode.STEREO, 1));
            pcm.offer(token, chunk(44228, 4410, 44100));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(1040, AudioOutputMode.STEREO, 1));
            float first = ByteBuffer.wrap(driver.firstPlayed.getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            assertEquals(44101f, first);
            assertEquals(40, 1040 - (long) (first - 1) * 1000 / 44100);
            assertEquals(1, driver.playCalls);
        }
    }

    @Test void seekAndReplacementWhileWaitingCannotStartTheOldHeadOrItsLateEof() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var old = pcm.reset(1);
        pcm.offer(old, chunk(0, 128, 44100));
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(0, AudioOutputMode.STEREO, 1));
            var seek = pcm.reset(1); pcm.offer(seek, chunk(44100, 128, 44100)); pcm.finish(seek, null);
            pcm.finish(old, new IllegalStateException("late"));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(1000, AudioOutputMode.STEREO, 1));
            assertEquals(44101f, ByteBuffer.wrap(driver.firstPlayed.getFirst().bytes()).order(ByteOrder.LITTLE_ENDIAN).getFloat());
            var replacement = pcm.reset(2); pcm.offer(replacement, chunk(0, 4410, 44100));
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(0, AudioOutputMode.STEREO, 1));
            assertEquals(1, driver.playCalls); assertNull(pcm.failure(replacement));
        }
    }

    @Test void closingDuringPrebufferNeverStartsOutput() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        pcm.offer(token, chunk(0, 128, 44100));
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1);
        assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(0, AudioOutputMode.STEREO, 1));
        controller.close(); pcm.offer(token, chunk(128, 4410, 44100));
        assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(0, AudioOutputMode.STEREO, 1));
        assertEquals(0, driver.creations);
    }
}
