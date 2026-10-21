package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL10;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackChunkTimingTest {
    private static int[] measuredFrames() throws Exception {
        try (var input = PcmPlaybackChunkTimingTest.class.getResourceAsStream("/audio/variable-vorbis-chunk-frames.txt")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank()).mapToInt(Integer::parseInt).toArray();
        }
    }

    @Test void measuredVariableChunksNeedTimeSizedDeviceBuffersAtFortyMillisecondTicks() throws Exception {
        int[] frames = measuredFrames();
        assertEquals(1024, Arrays.stream(frames, 0, 8).sum());
        assertEquals(2944, Arrays.stream(frames, 0, 16).sum());
        // The former eight arbitrary decoder chunks are shorter than a single
        // playback tick, even with an unlimited producer immediately available.
        var raw = new ClockedDriver(); raw.createSource();
        for (int i = 0; i < 8; i++) {
            int buffer = raw.createBuffer();
            raw.upload(buffer, OpenAlFormatSelector.AL_FORMAT_STEREO_FLOAT32, new byte[frames[i] * 8], 44100);
            raw.queue(77, buffer);
        }
        raw.play(77); raw.advance(1764);
        assertEquals(AL10.AL_STOPPED, raw.state);
        assertEquals(1, raw.underruns);

        exercisePlayback(frames, 2);
        exercisePlayback(frames, 4);
    }

    private static void exercisePlayback(int[] frames, int channels) throws Exception {
        int format = OpenAlFormatSelector.select(channels, true), frameBytes = channels * 4;
        var pcm = new PcmPlaybackBuffer(60); var token = pcm.reset(1);
        int next = 0; long sourceFrame = 0;
        while (next < 16) {
            pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[frames[next] * frameBytes],
                    format, 44100, sourceFrame));
            sourceFrame += frames[next++];
        }
        var driver = new ClockedDriver();
        var listening = new PlaybackListeningLedger(); listening.begin(1, UUID.randomUUID());
        try (var controller = new PcmPlaybackController(pcm, driver, listening, 1)) {
            assertEquals(PcmPlaybackController.Result.BUFFERING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(0, driver.playCalls, "The initial sixteen variable chunks cover only 66ms");
            while (pcm.queuedChunks() < 60 && next < frames.length) {
                pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[frames[next] * frameBytes],
                        format, 44100, sourceFrame));
                sourceFrame += frames[next++];
            }
            assertEquals(PcmPlaybackController.Result.PLAYING, controller.tick(0, AudioOutputMode.MULTICHANNEL, 1));
            assertEquals(4410 * frameBytes, driver.uploads.getFirst().bytes().length);
            for (int tick = 1; tick <= 100; tick++) {
                while (pcm.queuedChunks() < 60 && next < frames.length) {
                    pcm.offer(token, new PcmPlaybackBuffer.Chunk(new byte[frames[next] * frameBytes],
                            format, 44100, sourceFrame));
                    sourceFrame += frames[next++];
                }
                driver.advance(1764); // 40ms at the real source sample rate.
                assertEquals(PcmPlaybackController.Result.PLAYING,
                        controller.tick(tick * 40L, AudioOutputMode.MULTICHANNEL, 1));
                assertEquals(0, driver.underruns, "Available decoded PCM must not be stranded behind eight tiny AL buffers");
            }
            assertEquals(1, driver.playCalls, "Normal refill does not pause, restart or replace the source");
            assertTrue(listening.playedMillis(1) >= 3999 && listening.playedMillis(1) <= 4000,
                    "Listening follows the merged chunk byte durations and actual offset, not decoder chunk count");
        }
    }

    static final class ClockedDriver extends PcmPlaybackControllerTest.FakeDriver {
        long offsetFrames;
        int underruns;
        long frames(int buffer) {
            Upload upload = bufferUploads.get(buffer);
            return upload.bytes().length / OpenAlFormatSelector.frameSize(upload.format());
        }
        void advance(long frames) {
            if (state != AL10.AL_PLAYING) return;
            long total = queue.stream().mapToLong(this::frames).sum();
            long position = offsetFrames + frames;
            if (position >= total) { exhaust(); offsetFrames = 0; underruns++; return; }
            offsetFrames = position;
            processed = 0;
            for (int buffer : queue) {
                if (position < frames(buffer)) break;
                position -= frames(buffer); processed++;
            }
        }
        @Override public int createSource() { offsetFrames = 0; return super.createSource(); }
        @Override public int unqueue(int source) {
            long count = frames(queue.getFirst());
            int buffer = super.unqueue(source);
            if (state == AL10.AL_PLAYING) offsetFrames -= count;
            return buffer;
        }
        @Override public float sourceFloat(int source, int property) { return (float) offsetFrames / 44100; }
    }
}
