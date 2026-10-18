package indi.mopelotus.musichud.audio.codec.internal;

import java.io.*;
import java.util.*;
import javazoom.jl.decoder.*;

/**
 * Exact timeline reader after opening compressed input at index.plan(target).byteOffset(). No
 * runtime threads.
 */
public final class IndexedMp3Decoder implements AutoCloseable {
  private final InputStream owned;
  private final Mp3FrameIndex index;
  private final Mp3FrameIndex.Plan plan;
  private final Bitstream bits;
  private final Decoder decoder = new Decoder();
  private final JLayerPcmBuffer output;
  private final Object lifecycle = new Object();
  private volatile boolean closed;
  private boolean reading, eof;
  private long frame, position;
  private byte[] pending = new byte[0];
  private int cursor;

  public IndexedMp3Decoder(InputStream ownedAtPlan, Mp3FrameIndex index, Mp3FrameIndex.Plan plan)
      throws IOException {
    this.owned = Objects.requireNonNull(ownedAtPlan);
    try {
      this.index = Objects.requireNonNull(index);
      this.plan = Objects.requireNonNull(plan);
      if (!index.plan(plan.audibleSample()).equals(plan))
        throw new IOException("Seek plan does not match index");
      this.frame = plan.encodedFrameIndex();
      this.position = plan.audibleSample();
      bits = new Bitstream(owned);
      output = new JLayerPcmBuffer(channels());
      decoder.setOutputBuffer(output);
    } catch (Throwable e) {
      try {
        owned.close();
      } catch (Throwable c) {
        e.addSuppressed(c);
      }
      throw e;
    }
  }

  public int sampleRate() {
    return index.metadata().header().sampleRate();
  }

  public int channels() {
    return index.metadata().header().channels();
  }

  public long framePosition() {
    synchronized (lifecycle) {
      return position;
    }
  }

  public int readChunk(byte[] bytes, int off, int len) throws IOException {
    Objects.checkFromIndexSize(off, len, bytes.length);
    int align = channels() * 2;
    len -= len % align;
    if (len == 0) throw new IllegalArgumentException("No complete PCM frame fits");
    synchronized (lifecycle) {
      if (closed) throw new IOException("Decoder closed");
      if (reading) throw new IOException("Concurrent reader");
      reading = true;
    }
    try {
      while (cursor == pending.length) {
        if (closed) throw new IOException("Decoder closed");
        if (eof || position == index.audibleFrames()) return -1;
        decodeFrame();
      }
      synchronized (lifecycle) {
        if (closed) throw new IOException("Decoder closed");
        int n = Math.min(len, pending.length - cursor);
        System.arraycopy(pending, cursor, bytes, off, n);
        cursor += n;
        position += n / align;
        return n;
      }
    } finally {
      synchronized (lifecycle) {
        reading = false;
      }
    }
  }

  private void decodeFrame() throws IOException {
    Header h;
    try {
      h = bits.readFrame();
    } catch (BitstreamException e) {
      throw new IOException("Invalid indexed MPEG input", e);
    }
    if (h == null) {
      eof = true;
      if (position < index.audibleFrames())
        throw new EOFException("Indexed MPEG ended before requested samples");
      return;
    }
    try {
      var expected = index.metadata().header();
      if (h.frequency() != sampleRate()
          || (h.mode() == Header.SINGLE_CHANNEL ? 1 : 2) != channels()
          || h.layer() != 3) throw new IOException("MPEG format changed after index creation");
      decoder.decodeFrame(h, bits);
      long rawFrameStart = frame * expected.samplesPerFrame();
      int actual = output.length() / channels();
      frame++;
      if (actual == 0)
        return; // Missing initial reservoir is preroll, not a hole in global encoded time.
      int from = (int) Math.max(0, Math.min(actual, plan.desiredRawSample() - rawFrameStart));
      long endRaw = index.rawStartSample() + index.audibleFrames();
      int to = (int) Math.max(from, Math.min(actual, endRaw - rawFrameStart));
      if (to <= from) return;
      pending = new byte[(to - from) * channels() * 2];
      cursor = 0;
      for (int i = 0; i < (to - from) * channels(); i++) {
        short s = output.samples[from * channels() + i];
        pending[2 * i] = (byte) s;
        pending[2 * i + 1] = (byte) (s >>> 8);
      }
    } catch (DecoderException e) {
      throw new IOException("Indexed MP3 decode failed", e);
    } finally {
      bits.closeFrame();
    }
  }

  public void close() throws IOException {
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
    }
    owned.close();
  }
}
