package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;

/** Owns one decoder for a playback generation; late opens close themselves instead of replacing it. */
final class PlaybackDecoderSlot {
    private long generation;
    private AudioDecoder decoder;

    synchronized void advance(long next) {
        if (next < generation) return;
        generation = next;
        AudioDecoder previous = decoder;
        decoder = null;
        close(previous);
    }

    synchronized boolean adopt(long expected, AudioDecoder opened) {
        if (expected != generation) { close(opened); return false; }
        AudioDecoder previous = decoder;
        decoder = opened;
        if (previous != opened) close(previous);
        return true;
    }

    synchronized AudioDecoder current() { return decoder; }

    private static void close(AudioDecoder decoder) {
        if (decoder != null) {
            try { decoder.close(); } catch (RuntimeException ignored) { }
        }
    }
}
