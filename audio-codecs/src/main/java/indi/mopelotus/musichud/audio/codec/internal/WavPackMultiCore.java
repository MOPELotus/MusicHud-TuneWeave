package indi.mopelotus.musichud.audio.codec.internal;

import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * Bounded demux-only multichannel aggregation over the frozen mono/stereo codec. Retains channel
 * mask, rejects unknown layouts and DSD; no new prediction/entropy DSP.
 */
public final class WavPackMultiCore implements AutoCloseable {
  private final InputStream input;
  private int channels, bits, rate;
  private long mask, total = -1, frames, returnedFrames;
  private boolean floating, closed;
  private byte[] pending;

  public WavPackMultiCore(InputStream input) throws IOException {
    this.input = input;
    try {
      pending = group();
      if (pending == null) throw new EOFException("Empty WavPack");
    } catch (IOException | RuntimeException e) {
      try {
        input.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  public int channels() {
    return channels;
  }

  public int bits() {
    return bits;
  }

  public int sampleRate() {
    return rate;
  }

  public long channelMask() {
    return mask;
  }

  public boolean isFloating() {
    return floating;
  }

  public long frames() {
    return returnedFrames;
  }

  private byte[] group() throws IOException {
    if (total >= 0 && frames == total) return null;
    List<byte[]> blocks = new ArrayList<>();
    List<Integer> widths = new ArrayList<>();
    int groupCh = 0, count = -1, groupBits = 0, groupRate = 0;
    boolean groupFloat = false;
    long declaredMask = 0;
    int declaredChannels = 0;
    for (int n = 0; n < 8; n++) {
      byte[] h = input.readNBytes(32);
      if (h.length == 0 && n == 0) {
        if (total >= 0 && frames != total) throw new EOFException("Truncated WavPack audio");
        return null;
      }
      if (h.length < 32) throw new EOFException("Truncated WavPack group");
      ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
      if (b.getInt(0) != 0x6b707677) throw new IOException("Invalid WavPack signature");
      int flags = b.getInt(24), version = Short.toUnsignedInt(b.getShort(8));
      long size = Integer.toUnsignedLong(b.getInt(4)) + 8,
          index = Integer.toUnsignedLong(b.getInt(16)),
          samples = Integer.toUnsignedLong(b.getInt(20)),
          length = Integer.toUnsignedLong(b.getInt(12));
      if (version < 0x402 || version > 0x410 || (flags & 0x80000000) != 0)
        throw new IOException("Unsupported WavPack version/DSD");
      if (size < 32 || size > 8388608 || samples < 1 || samples > 131072 || index != frames)
        throw new IOException("Invalid WavPack block bounds");
      if (n == 0 && (flags & 0x800) == 0 || n > 0 && (flags & 0x800) != 0)
        throw new IOException("Invalid WavPack group boundary");
      if (count >= 0 && samples != count) throw new IOException("Inconsistent group sample count");
      count = (int) samples;
      if (length != 0 && length != 0xffffffffL) {
        if (total >= 0 && length != total) throw new IOException("Inconsistent total length");
        total = length;
      }
      byte[] body = input.readNBytes((int) size - 32);
      if (body.length != size - 32) throw new EOFException("Truncated WavPack body");
      for (int p = 0; p < body.length; ) {
        int idPos = p;
        if (body.length - p < 2) throw new EOFException("Short metadata");
        int id = body[p++] & 255, len = (body[p++] & 255) * 2;
        if ((id & 128) != 0) {
          if (body.length - p < 2) throw new EOFException("Short metadata length");
          len += (body[p++] & 255) << 9;
          len += (body[p++] & 255) << 17;
        }
        int stored = len;
        len -= ((id & 64) != 0 ? 1 : 0);
        if (len < 0 || stored > body.length - p) throw new EOFException("Metadata exceeds block");
        if ((id & 63) == 13) {
          if (len < 1 || len > 5) throw new IOException("Unsupported channel info");
          declaredChannels = body[p] & 255;
          declaredMask = 0;
          for (int j = 1; j < len; j++) declaredMask |= (long) (body[p + j] & 255) << (8 * (j - 1));
          body[idPos] =
              (byte) (id & 0xc0); /* Ignore global channel info inside two-channel DSP core. */
        }
        p += stored;
      }
      b.putInt(12, count);
      b.putInt(16, 0);
      b.putInt(24, flags | 0x1800);
      byte[] encoded = new byte[(int) size];
      System.arraycopy(h, 0, encoded, 0, 32);
      System.arraycopy(body, 0, encoded, 32, body.length);
      try (var codec = new WavPackVariantCore(new ByteArrayInputStream(encoded))) {
        int ch = codec.channels();
        if (n == 0) {
          groupBits = codec.bits();
          groupRate = codec.sampleRate();
          groupFloat = codec.isFloating();
        } else if (codec.bits() != groupBits
            || codec.sampleRate() != groupRate
            || codec.isFloating() != groupFloat)
          throw new IOException("WavPack substream format mismatch");
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(count * ch * (groupBits / 8));
        byte[] data;
        while ((data = codec.next()) != null) decoded.write(data);
        if (decoded.size() != count * ch * (groupBits / 8))
          throw new IOException("Substream frame mismatch");
        blocks.add(decoded.toByteArray());
        widths.add(ch);
        groupCh += ch;
        if (groupCh > 8) throw new IOException("Too many channels");
      }
      if ((flags & 0x1000) != 0) break;
      if (n == 7) throw new IOException("Too many WavPack channel blocks");
    }
    if (declaredChannels > 0 && groupCh != declaredChannels)
      throw new IOException("Declared channel count mismatch");
    long groupMask = declaredMask;
    if (groupCh <= 2 && groupMask == 0) groupMask = groupCh == 1 ? 4 : 3;
    if (Long.bitCount(groupMask) != groupCh)
      throw new IOException("Unknown WavPack channel layout");
    if (channels != 0
        && (channels != groupCh
            || bits != groupBits
            || rate != groupRate
            || floating != groupFloat
            || mask != groupMask)) throw new IOException("WavPack stream format changed");
    channels = groupCh;
    bits = groupBits;
    rate = groupRate;
    floating = groupFloat;
    mask = groupMask;
    int bytes = bits / 8;
    byte[] pcm = new byte[count * channels * bytes];
    for (int frame = 0; frame < count; frame++) {
      int outChannel = 0;
      for (int n = 0; n < blocks.size(); n++) {
        int ch = widths.get(n);
        System.arraycopy(
            blocks.get(n),
            frame * ch * bytes,
            pcm,
            (frame * channels + outChannel) * bytes,
            ch * bytes);
        outChannel += ch;
      }
    }
    frames += count;
    if (total >= 0 && frames > total) throw new IOException("Audio exceeds declared length");
    return pcm;
  }

  public byte[] next() throws IOException {
    if (closed) return null;
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Decode interrupted");
    byte[] data;
    if (pending != null) {
      data = pending;
      pending = null;
    } else data = group();
    if (data != null) returnedFrames += data.length / (channels * (bits / 8));
    return data;
  }

  public void close() throws IOException {
    if (!closed) {
      closed = true;
      pending = null;
      input.close();
    }
  }
}
