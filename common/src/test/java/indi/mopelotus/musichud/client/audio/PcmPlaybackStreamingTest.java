package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL10;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackStreamingTest {
    private static PcmPlaybackBuffer.Chunk chunk(long start, int frames) {
        var bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (long i = start; i < start + frames; i++) bytes.putShort((short) i).putShort((short) -i);
        return new PcmPlaybackBuffer.Chunk(bytes.array(), AL10.AL_FORMAT_STEREO16, 8000, start);
    }

    @Test void realtimeAndSlowerArrivalsHaveBoundedAdmissionAndNeverUploadExpiredFrames() throws Exception {
        for (int capacity : new int[]{2, 60}) for (int step : new int[]{100, 133, 200}) {
            var pcm = new PcmPlaybackBuffer(capacity); var token = pcm.reset(1);
            var driver = new PcmPlaybackControllerTest.FakeDriver(); var clock = new AtomicLong();
            var ledger = new PlaybackListeningLedger(); ledger.begin(1, UUID.randomUUID());
            try (var controller = new PcmPlaybackController(pcm, driver, ledger, 1, clock::get)) {
                pcm.offer(token, chunk(0, 800));
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.STEREO, 1));
                driver.exhaust();
                assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(100, AudioOutputMode.STEREO, 1));
                int resumed = 0;
                for (int i = 1; i <= 20; i++) {
                    long wall = 100L + i * step;
                    clock.set(i * step * 1_000_000L);
                    pcm.offer(token, chunk(i * 800L, 800));
                    int before = driver.uploads.size();
                    var result = controller.tick(wall, AudioOutputMode.STEREO, 1);
                    long earliest = Math.max(0, wall - 500) * 8;
                    for (int k = before; k < driver.uploads.size(); k++) {
                        var data = ByteBuffer.wrap(driver.uploads.get(k).bytes()).order(ByteOrder.LITTLE_ENDIAN);
                        assertTrue(Short.toUnsignedInt(data.getShort()) >= earliest,
                                "Only the declared wall-clock lookbehind is eligible");
                    }
                    if (result == PcmPlaybackController.Result.PLAYING) { resumed++; driver.exhaust(); }
                    assertTrue(pcm.queuedChunks() <= capacity + 8);
                    assertTrue(ledger.playedMillis(1) <= (i + 1) * 100L,
                            "Waiting, trimming and queued PCM cannot count as listening");
                }
                assertTrue(resumed > 0, "Even a slow source must resume when valid PCM exists at the wait boundary");
                if (step == 100) assertTrue(resumed >= 4, "The original twenty-second perpetual buffering regression");
                assertEquals(token, pcm.token());
            }
        }
    }

    @Test void delayedProducerCanCatchUpWithoutAnOldUnderrunTimerCrossingASeek() throws Exception {
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        var driver = new PcmPlaybackControllerTest.FakeDriver(); var clock = new AtomicLong();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1, clock::get)) {
            pcm.offer(token, chunk(0, 800)); controller.tick(0, AudioOutputMode.STEREO, 1); driver.exhaust();
            controller.tick(100, AudioOutputMode.STEREO, 1);
            clock.set(5_000_000_000L);
            pcm.offer(token, chunk(800, 800));
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(5000, AudioOutputMode.STEREO, 1));
            assertEquals(1, driver.uploads.size(), "Old data is discarded, never played merely because waiting expired");
            pcm.offer(token, chunk(40000, 800));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(5000, AudioOutputMode.STEREO, 1));
            var seek = pcm.reset(1); assertNotEquals(token, seek);
            pcm.offer(seek, chunk(80000, 80)); pcm.finish(seek, null);
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(10000, AudioOutputMode.STEREO, 1));
            assertFalse(pcm.offer(token, chunk(0, 800)));
        }
    }

    @Test void exhaustionDuringUploadOrQueueNeverReplaysOldBuffersOrCreditsUnconfirmedNewPcm() throws Exception {
        for (boolean duringUpload : new boolean[]{true, false}) {
            var pcm = new PcmPlaybackBuffer(8); var token = pcm.reset(1);
            pcm.offer(token, chunk(0, 800)); pcm.offer(token, chunk(800, 800));
            var driver = new PcmPlaybackControllerTest.FakeDriver();
            var ledger = new PlaybackListeningLedger(); ledger.begin(1, UUID.randomUUID());
            try (var controller = new PcmPlaybackController(pcm, driver, ledger, 1)) {
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.STEREO, 1));
                pcm.offer(token, chunk(1600, 800)); pcm.finish(token, null);
                driver.offsetSeconds = .05f;
                Runnable exhaust = () -> { driver.onUpload = () -> {}; driver.afterQueue = () -> {}; driver.exhaust(); };
                if (duringUpload) driver.onUpload = exhaust; else driver.afterQueue = exhaust;
                assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(100, AudioOutputMode.STEREO, 1));
                assertEquals(1, driver.playCalls, "No alSourcePlay on the exhausted source");
                assertEquals(50, ledger.playedMillis(1), "Only the earlier observed offset is audible evidence");
                assertEquals(0, pcm.peek(token).startFrame(), "Unconfirmed mirror is retained until explicit clock alignment");
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(200, AudioOutputMode.STEREO, 1));
                assertEquals(2, driver.playCalls);
                assertEquals(1600, Short.toUnsignedInt(ByteBuffer.wrap(driver.firstPlayed.getLast().bytes())
                        .order(ByteOrder.LITTLE_ENDIAN).getShort()), "Exactly source frames [0,1600) expired; new [1600,2400) survives");
                assertEquals(50, ledger.playedMillis(1), "Requeue cannot erase earlier listening or create new credit");
                driver.exhaust();
                assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(300, AudioOutputMode.STEREO, 1));
                assertEquals(150, ledger.playedMillis(1));
            }
        }
    }

    @Test void exhaustionAfterPlayingConfirmationIsConsumedOnceIncludingTheEofTail() throws Exception {
        var pcm = new PcmPlaybackBuffer(8); var token = pcm.reset(1);
        pcm.offer(token, chunk(0, 800)); var driver = new PcmPlaybackControllerTest.FakeDriver();
        var ledger = new PlaybackListeningLedger(); ledger.begin(1, UUID.randomUUID());
        try (var controller = new PcmPlaybackController(pcm, driver, ledger, 1)) {
            controller.tick(0, AudioOutputMode.STEREO, 1);
            pcm.offer(token, chunk(800, 800)); pcm.finish(token, null);
            driver.afterStateQuery = () -> { driver.afterStateQuery = () -> {}; driver.exhaust(); };
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(50, AudioOutputMode.STEREO, 1));
            assertEquals(1, driver.playCalls);
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(200, AudioOutputMode.STEREO, 1));
            assertEquals(200, ledger.playedMillis(1));
            assertEquals(1, driver.playCalls);
        }
    }

    @Test void epochLossDuringRefillAndReplacementRejectsEveryOldQueueAndLateCompletion() throws Exception {
        var pcm = new PcmPlaybackBuffer(8); var old = pcm.reset(1);
        pcm.offer(old, chunk(0, 800));
        var driver = new PcmPlaybackControllerTest.FakeDriver();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1)) {
            controller.tick(0, AudioOutputMode.STEREO, 1);
            pcm.offer(old, chunk(800, 800));
            driver.onUpload = () -> { driver.onUpload = () -> {}; driver.reload(); };
            assertEquals(PcmPlaybackController.Result.RECOVERING, controller.tick(100, AudioOutputMode.STEREO, 1));
            assertEquals(0, driver.deletions, "A retired context must not delete reused native IDs");
            var next = pcm.reset(2); pcm.offer(next, chunk(8000, 800));
            pcm.finish(old, new IllegalStateException("late EOF"));
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(1000, AudioOutputMode.STEREO, 1));
            assertEquals(1, driver.playCalls);
            assertEquals(8000, pcm.peek(next).startFrame());
            assertNull(pcm.failure(next));
        }
    }
}
