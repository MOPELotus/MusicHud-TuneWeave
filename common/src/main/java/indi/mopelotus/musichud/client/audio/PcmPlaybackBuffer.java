package indi.mopelotus.musichud.client.audio;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Bounded decoded PCM plus a mirror of the device queue, all scoped to a playback generation. */
final class PcmPlaybackBuffer {
    record Token(long generation, long decoder) {}
    record Chunk(byte[] bytes, int format, int sampleRate, long startFrame) {
        Chunk {
            if (bytes == null || bytes.length == 0 || bytes.length % OpenAlFormatSelector.frameSize(format) != 0
                    || sampleRate < 8000 || sampleRate > 384000 || startFrame < 0)
                throw new IllegalArgumentException("Invalid PCM chunk");
        }
        long frames() { return bytes.length / OpenAlFormatSelector.frameSize(format); }
        Chunk trimBefore(long millis) {
            long target = frameAt(millis, sampleRate);
            long skip = Math.max(0, target - startFrame);
            if (skip == 0) return this;
            if (skip >= frames()) return null;
            return new Chunk(Arrays.copyOfRange(bytes, Math.toIntExact(skip * OpenAlFormatSelector.frameSize(format)), bytes.length),
                    format, sampleRate, startFrame + skip);
        }
    }

    private final int capacity;
    private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
    private final LinkedHashMap<Integer, Chunk> device = new LinkedHashMap<>();
    private Token token = new Token(-1, 0);
    private boolean ended;
    private Throwable failure;

    PcmPlaybackBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    synchronized Token reset(long generation) {
        if (generation < token.generation()) return token;
        token = new Token(generation, token.decoder() + 1);
        pending.clear(); device.clear(); ended = false; failure = null;
        notifyAll();
        return token;
    }
    synchronized Token token() { return token; }
    synchronized boolean current(Token expected) { return token.equals(expected); }
    synchronized boolean offer(Token expected, Chunk chunk) throws InterruptedException {
        while (current(expected) && !ended && pending.size() >= capacity) wait(100);
        if (!current(expected) || ended) return false;
        pending.addLast(chunk);
        notifyAll();
        return true;
    }
    synchronized Chunk peek(Token expected) { return current(expected) ? pending.peekFirst() : null; }
    synchronized Chunk claim(Token expected, int bufferId) {
        if (!current(expected)) return null;
        Chunk chunk = pending.pollFirst();
        if (chunk != null) device.put(bufferId, chunk);
        notifyAll();
        return chunk;
    }
    synchronized void processed(Token expected, int bufferId) {
        if (current(expected)) device.remove(bufferId);
    }
    synchronized void recover(Token expected) {
        if (!current(expected)) return;
        var combined = new ArrayDeque<>(device.values());
        combined.addAll(pending);
        pending.clear(); pending.addAll(combined); device.clear();
        notifyAll();
    }
    synchronized void align(Token expected, long millis) {
        if (!current(expected)) return;
        while (!pending.isEmpty()) {
            Chunk first = pending.removeFirst();
            Chunk trimmed = first.trimBefore(millis);
            if (trimmed != null) { pending.addFirst(trimmed); break; }
        }
        notifyAll();
    }
    synchronized void finish(Token expected, Throwable error) {
        if (!current(expected) || ended) return;
        ended = true; failure = error; notifyAll();
    }
    synchronized boolean finished(Token expected) { return current(expected) && ended; }
    synchronized boolean empty(Token expected) { return current(expected) && pending.isEmpty() && device.isEmpty(); }
    synchronized Throwable failure(Token expected) { return current(expected) ? failure : null; }
    synchronized int queuedChunks() { return pending.size() + device.size(); }

    synchronized void abandon(Token expected, Throwable error) {
        if (!current(expected)) return;
        pending.clear(); device.clear(); ended = true; failure = error; notifyAll();
    }

    static long frameAt(long millis, int sampleRate) {
        if (millis <= 0) return 0;
        long seconds = millis / 1000;
        if (seconds > (Long.MAX_VALUE - sampleRate) / sampleRate) return Long.MAX_VALUE;
        return seconds * sampleRate + (millis % 1000) * sampleRate / 1000;
    }
}
