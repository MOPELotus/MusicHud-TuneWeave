package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.client.audio.decoder.flac.decode.AbstractFlacLowLevelInput;
import java.io.*;

/** Non-seekable input with the FLAC 24-bit frame-size ceiling and cancellation checks. */
final class StreamingFlacInput extends AbstractFlacLowLevelInput {
    private final InputStream input;
    private long bytesRead, frameStart;
    StreamingFlacInput(InputStream input) { this.input = input; }
    void beginFrame() { frameStart = getPosition(); }
    @Override public long getLength() { throw new UnsupportedOperationException("Streaming FLAC"); }
    @Override public void seekTo(long position) { throw new UnsupportedOperationException("Streaming FLAC"); }
    @Override protected int readUnderlying(byte[] buffer, int offset, int length) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FLAC decode cancelled");
        // The low-level reader prefetches at most 4096 bytes beyond the current frame.
        if (bytesRead - frameStart > 0xffffffL + 4096) throw new IOException("FLAC frame exceeds byte budget");
        int count = input.read(buffer, offset, length);
        if (count > 0) bytesRead += count;
        return count;
    }
    @Override public void close() throws IOException { try { input.close(); } finally { super.close(); } }
}
