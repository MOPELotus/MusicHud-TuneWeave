package indi.mopelotus.musichud.audio.codec.internal;

import java.io.*;
import java.util.*;
import javazoom.jl.decoder.*;

/** Synchronous JLayer adapter. Serial calls per instance, owned input. */
public final class JLayerStreamDecoder implements AutoCloseable {
  public enum TimingMode {
    EMBEDDED_GAPLESS,
    RAW_MEDIA
  }

  // Measured independently across MPEG 1/2/2.5 at eight rates: raw-reference lag minus
  // metadata-frame and declared encoder delay.
  private static final int SYNTHESIS_DELAY = 529;
  private final InputStream owned;
  private final Bitstream bits;
  private final Decoder decoder = new Decoder();
  private final JLayerPcmBuffer output;
  private final Mp3Headers.Metadata metadata;
  private final long expected;
  private long toSkip;
  private volatile long emitted;
  private byte[] pending = new byte[0];
  private int pendingOffset;
  private final Object lifecycle = new Object();
  private volatile boolean closed;
  private boolean reading, eof;

  public JLayerStreamDecoder(InputStream input) throws IOException {
    this(input, TimingMode.EMBEDDED_GAPLESS);
  }

  public JLayerStreamDecoder(InputStream input, TimingMode timingMode) throws IOException {
    Objects.requireNonNull(timingMode);
    owned = Objects.requireNonNull(input);
    try {
      PushbackInputStream stream =
          new PushbackInputStream(new BufferedInputStream(new ProgressInputStream(input)), 8192);
      long offset = Mp3Headers.skipTags(stream);
      byte[] four = stream.readNBytes(4);
      Mp3Headers.Header h = four.length == 4 ? Mp3Headers.parse(four, 0) : null;
      int search = 0;
      while (h == null && four.length == 4 && search++ < 65536) {
        int b = stream.read();
        if (b < 0) break;
        System.arraycopy(four, 1, four, 0, 3);
        four[3] = (byte) b;
        offset++;
        h = Mp3Headers.parse(four, 0);
      }
      if (h == null) throw new IOException("No supported MPEG Layer III frame");
      byte[] frame = new byte[h.frameBytes()];
      System.arraycopy(four, 0, frame, 0, 4);
      if (stream.readNBytes(frame, 4, frame.length - 4) != frame.length - 4)
        throw new EOFException("Truncated first MPEG frame");
      metadata = Mp3Headers.inspect(frame, h, offset);
      stream.unread(frame);
      bits = new Bitstream(new FrameBoundaryInputStream(stream, h));
      output = new JLayerPcmBuffer(h.channels());
      decoder.setOutputBuffer(output);
      boolean info =
          timingMode == TimingMode.EMBEDDED_GAPLESS
              && (metadata.vbrKind().equals("Xing") || metadata.vbrKind().equals("Info"));
      boolean gapless =
          timingMode == TimingMode.EMBEDDED_GAPLESS
              && metadata.declaredFrames() > 0
              && metadata.encoderDelay() >= 0
              && metadata.encoderPadding() >= 0;
      toSkip =
          (info ? h.samplesPerFrame() : 0)
              + (gapless ? metadata.encoderDelay() + SYNTHESIS_DELAY : 0);
      expected =
          gapless
              ? metadata.declaredFrames() * h.samplesPerFrame()
                  - metadata.encoderDelay()
                  - metadata.encoderPadding()
              : -1;
      if (gapless && expected < 0) throw new IOException("Invalid gapless declaration");
    } catch (Throwable e) {
      try {
        owned.close();
      } catch (Throwable close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  public Mp3Headers.Metadata metadata() {
    return metadata;
  }

  public int channels() {
    return metadata.header().channels();
  }

  public int sampleRate() {
    return metadata.header().sampleRate();
  }

  public long framePosition() {
    return emitted;
  }

  public int encoderDelay() {
    return metadata.encoderDelay();
  }

  public int encoderPadding() {
    return metadata.encoderPadding();
  }

  public byte[] readChunk() throws IOException {
    byte[] b = new byte[1152 * channels() * 2];
    int n = readChunk(b, 0, b.length);
    return n < 0 ? null : n == b.length ? b : Arrays.copyOf(b, n);
  }

  public int readChunk(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    int alignment = channels() * 2;
    length -= length % alignment;
    if (length == 0) throw new IllegalArgumentException("Target must fit complete PCM frame");
    synchronized (lifecycle) {
      if (closed) throw new IOException("Decoder closed");
      if (reading) throw new IOException("Concurrent read on one decoder");
      reading = true;
    }
    try {
      while (pendingOffset == pending.length) {
        if (closed) throw new IOException("Decoder closed");
        if (eof) return -1;
        readFrame();
      }
      synchronized (lifecycle) {
        if (closed) throw new IOException("Decoder closed");
        int n = Math.min(length, pending.length - pendingOffset);
        System.arraycopy(pending, pendingOffset, target, offset, n);
        pendingOffset += n;
        emitted += n / alignment;
        return n;
      }
    } finally {
      synchronized (lifecycle) {
        reading = false;
      }
    }
  }

  private void readFrame() throws IOException {
    Header header;
    try {
      header = bits.readFrame();
    } catch (BitstreamException e) {
      throw new IOException("Invalid MPEG bitstream", e);
    }
    if (header == null) {
      eof = true;
      if (emitted == 0) throw new EOFException("MP3 stream produced no PCM");
      if (expected >= 0 && emitted != expected)
        throw new EOFException(
            "MP3 sample count " + emitted + " differs from gapless declaration " + expected);
      return;
    }
    try {
      if (header.frequency() != sampleRate()
          || (header.mode() == Header.SINGLE_CHANNEL ? 1 : 2) != channels())
        throw new IOException("MPEG format changed midstream");
      decoder.decodeFrame(header, bits);
      int total = output.length() / channels();
      int skip = (int) Math.min(toSkip, total);
      toSkip -= skip;
      int take = total - skip;
      if (expected >= 0) take = (int) Math.max(0, Math.min(take, expected - emitted));
      pending = new byte[take * channels() * 2];
      pendingOffset = 0;
      short[] pcm = output.samples;
      for (int i = 0; i < take * channels(); i++) {
        short value = pcm[skip * channels() + i];
        pending[i * 2] = (byte) value;
        pending[i * 2 + 1] = (byte) (value >>> 8);
      }
    } catch (DecoderException e) {
      throw new IOException("MP3 decode failed", e);
    } finally {
      bits.closeFrame();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
    }
    // Bitstream.close only forwards input.close; do not mutate its parser while the reader is
    // active.
    owned.close();
  }

  /**
   * Check aligned frame formats before Bitstream strict-sync can silently discard a legal new
   * format. Unknown non-MPEG footer bytes remain delegated to the mature parser.
   */
  private static final class FrameBoundaryInputStream extends InputStream {
    final PushbackInputStream in;
    final Mp3Headers.Header format;
    byte[] frame = new byte[0];
    int cursor;
    boolean tail;

    FrameBoundaryInputStream(PushbackInputStream in, Mp3Headers.Header format) {
      this.in = in;
      this.format = format;
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      if (tail && cursor == frame.length) return in.read(b, off, len);
      while (cursor == frame.length) {
        byte[] head = in.readNBytes(4);
        if (head.length == 0) return -1;
        if (head.length == 4 && head[0] == 'T' && head[1] == 'A' && head[2] == 'G') {
          in.skipNBytes(124);
          continue;
        }
        if (head.length == 4 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
          in.unread(head);
          Mp3Headers.skipTags(in);
          continue;
        }
        Mp3Headers.Header h = head.length == 4 ? Mp3Headers.parse(head, 0) : null;
        if (h == null) {
          frame = head;
          cursor = 0;
          tail = true;
          break;
        }
        if (!h.version().equals(format.version())
            || h.sampleRate() != format.sampleRate()
            || h.channels() != format.channels())
          throw new IOException("MPEG format changed at frame boundary");
        frame = new byte[h.frameBytes()];
        cursor = 0;
        System.arraycopy(head, 0, frame, 0, 4);
        if (in.readNBytes(frame, 4, frame.length - 4) != frame.length - 4)
          throw new EOFException("Truncated MPEG frame");
      }
      int n = Math.min(len, frame.length - cursor);
      System.arraycopy(frame, cursor, b, off, n);
      cursor += n;
      return n;
    }
  }

  /**
   * Public Obuffer hook: recover full-scale float PCM before JLayer's default 32700-gain/truncating
   * SampleBuffer.
   */
  private static final class ProgressInputStream extends FilterInputStream {
    ProgressInputStream(InputStream in) {
      super(in);
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      for (int i = 0; i < 32; i++) {
        int n = in.read(b, off, len);
        if (n != 0) return n;
      }
      throw new IOException("Input made no progress");
    }
  }
}
