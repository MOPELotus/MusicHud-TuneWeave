package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackControllerTest {
    @Test void reloadReusesDecoderPcmAndTrimsInterleavedFramesWithoutDeletingReusedNames() throws Exception {
        PcmPlaybackBuffer pcm = new PcmPlaybackBuffer(16);
        var token = pcm.reset(1);
        for (int i = 0; i < 10; i++) pcm.offer(token, stereo(i * 800, 800));
        pcm.finish(token, null);
        FakeDriver driver = new FakeDriver();
        try (var controller = controller(pcm, driver)) {
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(8, driver.queue.size());
            driver.reload();
            driver.uploads.clear();
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(150, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(token, pcm.token(), "Recovery must not replace the decoder generation");
            assertEquals(0, driver.deletions, "Old epoch must not delete reused source IDs");
            var first = driver.uploads.getFirst();
            assertEquals(400 * 4, first.bytes.length);
            var samples = ByteBuffer.wrap(first.bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1200, samples.getShort()); assertEquals(-1200, samples.getShort());
            assertEquals(10, pcm.queuedChunks() + 1, "Only the elapsed first chunk was removed");
        }
        assertEquals(1, driver.deletions);
    }

    @Test void threeGenuineRejectionsDownmixRetainedPcmWithoutLosingAnySurroundSpeaker() throws Exception {
        for (int channels : new int[]{4, 6, 7, 8}) {
            var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
            ByteBuffer data = ByteBuffer.allocate(channels * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int frame = 0; frame < channels; frame++) for (int speaker = 0; speaker < channels; speaker++)
                data.putFloat(frame == speaker ? .5f : 0);
            pcm.offer(token, new PcmPlaybackBuffer.Chunk(data.array(), OpenAlFormatSelector.select(channels, true), 8000, 0));
            pcm.finish(token, null);
            var driver = new FakeDriver(); driver.reject = format -> OpenAlFormatSelector.channels(format) > 2;
            try (var controller = controller(pcm, driver)) {
                for (int i = 0; i < 3; i++) assertEquals(PcmPlaybackController.Result.RECOVERING,
                        controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                assertEquals(3, driver.rejections);
                var output = driver.uploads.getFirst();
                assertEquals(OpenAlFormatSelector.AL_FORMAT_STEREO_FLOAT32, output.format);
                ByteBuffer stereo = ByteBuffer.wrap(output.bytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int frame = 0; frame < channels; frame++) {
                    float left = stereo.getFloat(), right = stereo.getFloat();
                    assertTrue(left + right > 0, "No speaker may be dropped");
                    if (frame == 0) { assertTrue(left > 0); assertEquals(0, right); }
                    if (frame == 1) { assertTrue(right > 0); assertEquals(0, left); }
                }
            }
        }
    }

    @Test void userStereoAndMissingCapabilitiesConvertOnlyAtDeviceBoundary() throws Exception {
        for (boolean capability : new boolean[]{false, true}) {
            var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
            byte[] data = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putFloat(.5f)
                    .putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0).array();
            pcm.offer(token, new PcmPlaybackBuffer.Chunk(data, OpenAlFormatSelector.select(6, true), 48000, 0));
            var driver = new FakeDriver(); driver.capability = capability;
            try (var controller = controller(pcm, driver)) {
                controller.tick(0, AudioOutputMode.STEREO, 1);
                var uploaded = driver.uploads.getFirst();
                assertEquals(OpenAlFormatSelector.select(2, capability), uploaded.format);
                assertEquals(capability ? 8 : 4, uploaded.bytes.length);
                assertEquals(0, driver.rejections);
            }
        }
    }

    @Test void oldAlErrorIsClearedAndContextLossDoesNotCountTowardFormatFallback() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[24], OpenAlFormatSelector.select(6, true), 8000, 0));
        var driver = new FakeDriver(); driver.error = AL10.AL_INVALID_ENUM;
        try (var controller = controller(pcm, driver)) {
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            for (int i = 0; i < 4; i++) {
                driver.reload();
                assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                assertEquals(6, OpenAlFormatSelector.channels(driver.uploads.getLast().format));
            }
        }
    }

    @Test void stopOrReplacementDuringReloadCannotReviveOldPcmAndLateDownload() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); var old = pcm.reset(1);
        pcm.offer(old, stereo(0, 800));
        var driver = new FakeDriver();
        try (var controller = controller(pcm, driver)) {
            controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
            driver.reload(); driver.available = false;
            assertEquals(PcmPlaybackController.Result.RECOVERING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            var next = pcm.reset(2); pcm.offer(next, stereo(2000, 800));
            assertFalse(pcm.offer(old, stereo(800, 800)));
            pcm.finish(old, new IllegalStateException("late failure"));
            driver.available = true;
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(1, driver.creations);
            assertEquals(2000, pcm.peek(next).startFrame());
            assertNull(pcm.failure(next));
        }
    }

    @Test void normalEofIncludingExpiredRecoveryCompletesButRealFailureRemainsAnError() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
        var driver = new FakeDriver();
        try (var controller = controller(pcm, driver)) {
            pcm.offer(token, stereo(0, 800)); pcm.finish(token, null);
            controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
            driver.reload();
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(200, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(1, driver.creations, "Elapsed EOF must not reopen the device or downloader");
        }
        var failed = new PcmPlaybackBuffer(1); var failedToken = failed.reset(1);
        failed.finish(failedToken, new IllegalArgumentException("broken input"));
        try (var controller = controller(failed, new FakeDriver())) {
            assertThrows(IllegalStateException.class, () -> controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
        }
    }

    @Test void unsupportedBaselineFormatAndOutOfMemoryAreBoundedInsteadOfEndlessRetry() throws Exception {
        for (int code : new int[]{AL10.AL_INVALID_ENUM, AL10.AL_OUT_OF_MEMORY}) {
            var pcm = new PcmPlaybackBuffer(2); pcm.offer(pcm.reset(1), stereo(0, 800));
            var driver = new FakeDriver(); driver.reject = ignored -> true; driver.rejectionCode = code;
            try (var controller = controller(pcm, driver)) {
                controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
                controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
                assertThrows(OpenAlFailure.class, () -> controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            }
        }
    }

    @Test void consumedFinalBufferCountsAsListeningBeforeNaturalEnd() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
        pcm.offer(token, stereo(0, 800)); pcm.finish(token, null);
        var driver = new FakeDriver();
        var listening = new PlaybackListeningLedger(); listening.begin(1, java.util.UUID.randomUUID());
        try (var controller = new PcmPlaybackController(pcm, driver, listening, 1)) {
            controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
            driver.processed = 1;
            assertEquals(PcmPlaybackController.Result.COMPLETED, controller.tick(100, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(100, listening.playedMillis(1));
        }
    }

    @Test void falseFloatCapabilityFallsBackAfterFormatRejectionAndKeepsFrameCount() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
        pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[240], OpenAlFormatSelector.select(6, true), 8000, 0));
        var driver = new FakeDriver(); driver.reject = OpenAlFormatSelector::floating;
        try (var controller = controller(pcm, driver)) {
            for (int i = 0; i < 6; i++) assertEquals(PcmPlaybackController.Result.RECOVERING,
                    controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(AL10.AL_FORMAT_STEREO16, driver.uploads.getFirst().format);
            assertEquals(40, driver.uploads.getFirst().bytes.length);
        }
    }

    @Test void missingDeviceAndRepeatedLostNamesHaveAMonotonicRecoveryDeadline() throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            var pcm = new PcmPlaybackBuffer(2); var token = pcm.reset(1);
            pcm.offer(token, stereo(0, 800));
            var driver = new FakeDriver(); driver.available = !missing; driver.invalidSources = !missing;
            var clock = new java.util.concurrent.atomic.AtomicLong();
            try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1, clock::get)) {
                assertEquals(PcmPlaybackController.Result.RECOVERING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                clock.set(29_000_000_000L);
                assertEquals(PcmPlaybackController.Result.RECOVERING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                clock.set(30_000_000_000L);
                var error = assertThrows(IllegalStateException.class, () -> controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
                assertTrue(error.getMessage().contains("30 seconds"));
                assertEquals(0, pcm.queuedChunks());
                assertSame(error, pcm.failure(token));
                assertFalse(pcm.offer(token, stereo(0, 800)));
            }
        }
    }

    @Test void successfulPlaybackResetsRecoveryBudgetForALaterReload() throws Exception {
        var pcm = new PcmPlaybackBuffer(2); pcm.offer(pcm.reset(1), stereo(0, 800));
        var driver = new FakeDriver(); driver.available = false;
        var clock = new java.util.concurrent.atomic.AtomicLong();
        try (var controller = new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1, clock::get)) {
            controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
            clock.set(29_000_000_000L); driver.available = true;
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            clock.set(30_000_000_000L); driver.reload(); driver.available = false;
            controller.tick(0, AudioOutputMode.MULTICHANNEL, 1);
            clock.set(59_000_000_000L);
            assertEquals(PcmPlaybackController.Result.RECOVERING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            clock.set(60_000_000_000L);
            assertThrows(IllegalStateException.class, () -> controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
        }
    }

    private static PcmPlaybackController controller(PcmPlaybackBuffer pcm, FakeDriver driver) {
        return new PcmPlaybackController(pcm, driver, new PlaybackListeningLedger(), 1);
    }
    private static PcmPlaybackBuffer.Chunk stereo(int start, int frames) {
        ByteBuffer bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = start; frame < start + frames; frame++) bytes.putShort((short) frame).putShort((short) -frame);
        return new PcmPlaybackBuffer.Chunk(bytes.array(), AL10.AL_FORMAT_STEREO16, 8000, start);
    }

    static final class FakeDriver implements OpenAlPlaybackDevice.Driver {
        record Upload(int format, byte[] bytes) {}
        long generation = 1;
        boolean available = true, capability = true, invalidSources;
        int error, creations, deletions, rejections, nextBuffer, processed, state = AL10.AL_STOPPED;
        int rejectionCode = AL10.AL_INVALID_ENUM;
        IntPredicate reject = ignored -> false;
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        final List<Upload> uploads = new ArrayList<>();
        void reload() { generation++; queue.clear(); state = AL10.AL_STOPPED; }
        public long context() { return 17; }
        public long generation() { return generation; }
        public boolean available() { return available; }
        public boolean floating() { return capability; }
        public boolean multichannel() { return capability; }
        public boolean directChannels() { return true; }
        public int error() { int previous = error; error = 0; return previous; }
        public int createSource() { creations++; nextBuffer = 0; if (invalidSources) error = AL10.AL_INVALID_NAME; return 77; }
        public int createBuffer() { return ++nextBuffer; }
        public void deleteSource(int source) { deletions++; queue.clear(); }
        public void deleteBuffer(int buffer) {}
        public void sourceInt(int source, int property, int value) {}
        public void sourceFloat(int source, int property, float value) {}
        public int sourceInt(int source, int property) {
            return switch (property) {
                case AL10.AL_BUFFERS_QUEUED -> queue.size();
                case AL10.AL_BUFFERS_PROCESSED -> processed;
                case AL10.AL_SOURCE_STATE -> state;
                default -> 0;
            };
        }
        public float sourceFloat(int source, int property) { return 0; }
        public void play(int source) { state = AL10.AL_PLAYING; }
        public void stop(int source) { state = AL10.AL_STOPPED; }
        public int unqueue(int source) { processed--; return queue.removeFirst(); }
        public void queue(int source, int buffer) { queue.addLast(buffer); }
        public void upload(int buffer, int format, byte[] pcm, int sampleRate) {
            if (reject.test(format)) { error = rejectionCode; rejections++; }
            else uploads.add(new Upload(format, pcm.clone()));
        }
    }
}
