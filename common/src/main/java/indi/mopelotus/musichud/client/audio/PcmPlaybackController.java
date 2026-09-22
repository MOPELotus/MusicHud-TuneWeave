package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import indi.mopelotus.musichud.client.audio.decoder.PcmDeviceConversion;
import java.util.function.LongSupplier;

/** Device recovery never owns or replaces a decoder, public session, or download worker. */
final class PcmPlaybackController implements AutoCloseable {
    enum Result { BUFFERING, PLAYING, RECOVERING, COMPLETED }
    private final PcmPlaybackBuffer pcm;
    private final OpenAlPlaybackDevice.Driver driver;
    private final PlaybackListeningLedger listening;
    private final long generation;
    static final long RECOVERY_BUDGET_NANOS = 30_000_000_000L;
    private final LongSupplier nanoTime;
    private boolean recovering;
    private long recoveryStarted;
    private final AudioOutputPolicy policy = new AudioOutputPolicy();
    private PcmPlaybackBuffer.Token token;
    private OpenAlPlaybackDevice device;
    private boolean align;
    private boolean closed;
    private boolean hasPlayed;
    private int operationFailures;

    PcmPlaybackController(PcmPlaybackBuffer pcm, OpenAlPlaybackDevice.Driver driver,
                          PlaybackListeningLedger listening, long generation) {
        this(pcm, driver, listening, generation, System::nanoTime);
    }
    PcmPlaybackController(PcmPlaybackBuffer pcm, OpenAlPlaybackDevice.Driver driver,
                          PlaybackListeningLedger listening, long generation, LongSupplier nanoTime) {
        this.pcm = pcm; this.driver = driver; this.listening = listening; this.generation = generation;
        this.nanoTime = nanoTime;
        token = pcm.token();
    }
    synchronized Result tick(long positionMillis, AudioOutputMode mode, float gain) {
        if (closed || pcm.token().generation() != generation) return Result.COMPLETED;
        if (!token.equals(pcm.token())) {
            releaseDevice(false); token = pcm.token(); align = true;
        }
        Throwable failure = pcm.failure(token);
        if (failure != null) throw new IllegalStateException("Audio download failed", failure);
        try {
            if (device != null && !device.current()) releaseDevice(true);
            if (align) { pcm.align(token, positionMillis); align = false; }
            if (pcm.finished(token) && pcm.empty(token)) return Result.COMPLETED;
            if (!driver.available()) { align = true; return recovering(); }
            if (device == null) {
                if (pcm.peek(token) == null) return Result.BUFFERING;
                device = new OpenAlPlaybackDevice(driver, 8);
                policy.device(device.context(), device.generation());
            }
            listening.observe(generation, device.offsetSeconds(), gain > 0);
            for (int buffer : device.processed()) {
                listening.processed(generation, buffer); pcm.processed(token, buffer);
            }
            listening.observe(generation, device.offsetSeconds(), gain > 0);
            if (device.queued() == 0) listening.discardQueued(generation);
            // Bound stale playback after stalls; every trim is an entire interleaved frame.
            pcm.align(token, Math.max(0, positionMillis - 500));
            if (hasPlayed && device.queued() == 0 && !pcm.readyAfterUnderrun(token))
                return Result.BUFFERING;
            while (device.hasSpace()) {
                PcmPlaybackBuffer.Chunk next = pcm.peek(token);
                if (next == null) break;
                int format = policy.format(next.format(), mode, device.multichannel(), device.floating());
                if (!device.matches(format, next.sampleRate())) {
                    releaseDevice(true);
                    return recovering();
                }
                int buffer = device.nextBuffer();
                PcmPlaybackBuffer.Chunk claimed = pcm.claim(token, buffer);
                if (claimed == null) break;
                byte[] data = PcmDeviceConversion.convert(claimed.bytes(), claimed.format(), format, claimed.sampleRate());
                device.queue(buffer, format, data, claimed.sampleRate());
                listening.queue(generation, buffer, data.length,
                        (long) claimed.sampleRate() * OpenAlFormatSelector.frameSize(format), true);
            }
            if (device.queued() > 0) {
                device.play(Math.clamp(gain, 0f, 1f));
                hasPlayed = true;
                policy.accepted(device.format());
                operationFailures = 0;
                recovering = false;
                return Result.PLAYING;
            }
            if (pcm.finished(token) && pcm.empty(token)) return Result.COMPLETED;
            return Result.BUFFERING;
        } catch (OpenAlFailure failureDuringPlayback) {
            boolean fallback = device != null && device.format() != -1
                    && policy.rejected(failureDuringPlayback, device.format());
            releaseDevice(true);
            if (fallback) operationFailures = 0;
            else if (!failureDuringPlayback.deviceLost() && ++operationFailures >= 3) throw failureDuringPlayback;
            return recovering();
        }
    }
    private Result recovering() {
        long now = nanoTime.getAsLong();
        if (!recovering) { recovering = true; recoveryStarted = now; }
        if (now - recoveryStarted >= RECOVERY_BUDGET_NANOS) {
            var timeout = new IllegalStateException("Audio device did not recover within 30 seconds");
            releaseDevice(false);
            pcm.abandon(token, timeout);
            closed = true;
            throw timeout;
        }
        return Result.RECOVERING;
    }
    private void releaseDevice(boolean recover) {
        if (recover) { pcm.recover(token); align = true; }
        listening.discardQueued(generation);
        if (device != null) { device.close(); device = null; }
    }
    @Override public synchronized void close() {
        closed = true; releaseDevice(false);
    }
}
