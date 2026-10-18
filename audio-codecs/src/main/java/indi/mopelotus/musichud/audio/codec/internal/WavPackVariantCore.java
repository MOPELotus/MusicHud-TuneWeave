package indi.mopelotus.musichud.audio.codec.internal;

import com.beatofthedrum.wv.*;
import java.io.*;
import java.nio.*;

/**
 * Bounded per-block wrapper: supplements Tiny Decoder's omitted integer WVX bits. Uses public Tiny
 * Decoder API; no modification to its DSP. Integer/float mono/stereo blocks and hybrid main-file
 * output. WVX arithmetic follows FFmpeg LGPL2.1+ libavcodec/wavpack.c wv_get_value_integer and
 * wavpackenc.c scan_int32 (CRC covers the full restored integer before post-shift).
 */
public final class WavPackVariantCore implements AutoCloseable {
  private final InputStream input;
  private WavPackContext context;
  private int flags,
      blockFrames,
      remaining,
      bits,
      channels,
      rate,
      extraBits,
      zeros,
      ones,
      dups,
      postShift;
  private long total = -1, delivered;
  private int metadataOnlyBlocks;
  private int floatFlags, floatShift, floatMaxExp;
  private boolean floating;
  private byte[] extra;
  private int bitCursor, extraExpected, extraCrc;
  private boolean closed;
  private final int[] samples = new int[Defines.SAMPLE_BUFFER_SIZE];

  public WavPackVariantCore(InputStream input) throws IOException {
    this.input = input;
    try {
      if (!loadBlock()) throw new EOFException("Empty WavPack");
    } catch (IOException | RuntimeException e) {
      try {
        input.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  public boolean isFloating() {
    return floating;
  }

  public int bits() {
    return bits;
  }

  public int channels() {
    return channels;
  }

  public int sampleRate() {
    return rate;
  }

  public long frames() {
    return delivered;
  }

  private boolean loadBlock() throws IOException {
    byte[] header = input.readNBytes(32);
    if (header.length == 0) {
      if (total >= 0 && delivered != total) throw new EOFException("Truncated WavPack frames");
      return false;
    }
    if (header.length < 32) throw new EOFException("Truncated WavPack header");
    if (header[0] != 'w' || header[1] != 'v' || header[2] != 'p' || header[3] != 'k')
      throw new IOException("Invalid WavPack signature");
    ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    long size = Integer.toUnsignedLong(h.getInt(4)) + 8;
    flags = h.getInt(24);
    int version = Short.toUnsignedInt(h.getShort(8));
    if (version < 0x402 || version > 0x410 || (flags & 0x80000000) != 0)
      throw new IOException("Unsupported WavPack version/DSD");
    long count = Integer.toUnsignedLong(h.getInt(20)), index = Integer.toUnsignedLong(h.getInt(16));
    if (size < 32 || size > 8 * 1024 * 1024 || count > 1048576)
      throw new IOException("WavPack block exceeds limits");
    byte[] body = input.readNBytes((int) size - 32);
    if (body.length != size - 32) throw new EOFException("Truncated WavPack block");
    if (count == 0) {
      if (++metadataOnlyBlocks > 128) throw new IOException("Too many metadata-only blocks");
      return loadBlock();
    }
    metadataOnlyBlocks = 0;
    if ((flags & 0x1800) != 0x1800)
      throw new IOException("Unqualified float/hybrid/multiblock WavPack");
    if (index != delivered) throw new IOException("Unexpected WavPack block index");
    floating = (flags & 0x80) != 0;
    floatFlags = floatShift = floatMaxExp = 0;
    extraBits = zeros = ones = dups = 0;
    extra = null;
    extraCrc = -1;
    bitCursor = 0;
    extraExpected = 0;
    postShift = (flags >>> 13) & 31;
    for (int p = 0; p < body.length; ) {
      if (body.length - p < 2) throw new EOFException("Short metadata header");
      int id = body[p++] & 255, len = (body[p++] & 255) * 2;
      if ((id & 0x80) != 0) {
        if (body.length - p < 2) throw new EOFException("Short metadata length");
        len += (body[p++] & 255) << 9;
        len += (body[p++] & 255) << 17;
      }
      int stored = len;
      if ((id & 0x40) != 0) len--;
      if (len < 0 || stored > body.length - p) throw new EOFException("Metadata exceeds block");
      int kind = id & 0x3f;
      if (kind == 8) {
        if (len != 4) throw new IOException("Invalid float info");
        floatFlags = body[p] & 255;
        floatShift = body[p + 1] & 255;
        floatMaxExp = body[p + 2] & 255;
        if (floatShift > 31 || (floatFlags & ~0x3f) != 0)
          throw new IOException("Invalid float flags");
      } else if (kind == 9) {
        if (len != 4) throw new IOException("Invalid integer info");
        extraBits = body[p] & 255;
        zeros = body[p + 1] & 255;
        ones = body[p + 2] & 255;
        dups = body[p + 3] & 255;
        if (extraBits > 30 || zeros + ones + dups + extraBits + postShift > 31)
          throw new IOException("Invalid extended shifts");
      } else if (kind == 12) {
        if (extra != null || len < 4) throw new IOException("Invalid WVX data");
        extraExpected = ByteBuffer.wrap(body, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        extra = java.util.Arrays.copyOfRange(body, p + 4, p + len);
      }
      p += stored;
    }
    if (extraBits > 0 && extra == null && (flags & 8) == 0)
      throw new IOException("Missing WVX precision bits");
    byte[] full = new byte[(int) size];
    System.arraycopy(header, 0, full, 0, 32);
    System.arraycopy(body, 0, full, 32, body.length);
    context = WavPackUtils.OpenFileInput(new DataInputStream(new ByteArrayInputStream(full)));
    if (context.error) throw new IOException(context.error_message);
    int ch = WavPackUtils.GetNumChannels(context),
        depth = floating ? 32 : WavPackUtils.GetBytesPerSample(context) * 8,
        sampleRate = (int) WavPackUtils.GetSampleRate(context);
    if (ch < 1 || ch > 2 || WavPackUtils.GetReducedChannels(context) != ch)
      throw new IOException("Unsupported WavPack channel layout");
    if (channels != 0 && (ch != channels || depth != bits || sampleRate != rate))
      throw new IOException("WavPack format changed");
    channels = ch;
    bits = depth;
    rate = sampleRate;
    long n = Integer.toUnsignedLong(h.getInt(12));
    if (n != 0 && n != 0xffffffffL) {
      if (total >= 0 && n != total) throw new IOException("Inconsistent WavPack length");
      total = n;
    }
    blockFrames = remaining = (int) count;
    return true;
  }

  private int extra(int count) throws IOException {
    if (count == 0) return 0;
    if (extra == null || bitCursor + count > extra.length * 8)
      throw new EOFException("Truncated WVX bits");
    int value = 0;
    for (int b = 0; b < count; b++, bitCursor++)
      value |= ((extra[bitCursor >>> 3] >>> (bitCursor & 7)) & 1) << b;
    return value;
  }

  private int floatBits(int sample) throws IOException {
    int sign = 0, exp = floatMaxExp;
    boolean hasExtra = extra != null;
    if (sample != 0) {
      sample *= 1 << floatShift;
      sign = sample < 0 ? 1 : 0;
      if (sign != 0) sample = -sample;
      if (Integer.toUnsignedLong(sample) >= 0x1000000L) {
        sample = hasExtra && extra(1) != 0 ? extra(23) : 0;
        exp = 255;
      } else if (exp != 0) {
        int shift = 23 - (31 - Integer.numberOfLeadingZeros(sample));
        if (exp <= shift) shift = --exp;
        exp -= shift;
        if (shift < 0 || shift > 23) throw new IOException("Invalid float exponent");
        if (shift != 0) {
          sample <<= shift;
          if ((floatFlags & 1) != 0 || (hasExtra && (floatFlags & 2) != 0 && extra(1) != 0))
            sample |= (1 << shift) - 1;
          else if (hasExtra && (floatFlags & 4) != 0) sample |= extra(shift);
        }
      }
      sample &= 0x7fffff;
    } else {
      exp = 0;
      if (hasExtra && (floatFlags & 8) != 0) {
        if (extra(1) != 0) {
          sample = extra(23);
          if (floatMaxExp >= 25) exp = extra(8);
          sign = extra(1);
        } else if ((floatFlags & 16) != 0) sign = extra(1);
      }
    }
    extraCrc = extraCrc * 27 + sample * 9 + exp * 3 + sign;
    return (sign << 31) | (exp << 23) | sample;
  }

  public byte[] next() throws IOException {
    if (closed) return null;
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("WavPack interrupted");
    if (remaining == 0) {
      if (total >= 0 && delivered == total) return null;
      if (!loadBlock()) return null;
    }
    int request = Math.min(remaining, samples.length / channels);
    long n = WavPackUtils.UnpackSamples(context, samples, request);
    if (n != request || context.error || WavPackUtils.GetNumErrors(context) > 0)
      throw new IOException("Corrupt WavPack samples");
    byte[] pcm = new byte[request * channels * (bits / 8)];
    for (int f = 0; f < request; f++)
      for (int ch = 0; ch < channels; ch++) {
        int v = samples[f * channels + ch];
        if (floating) {
          boolean duplicate = (flags & 0x40000000) != 0 && ch == 1;
          v = duplicate ? samples[f * channels] : floatBits(v);
          samples[f * channels + ch] = v;
        } else if (extraBits > 0 && (flags & 8) == 0) {
          boolean duplicate = (flags & 0x40000000) != 0 && ch == 1;
          if (duplicate) v = samples[f * channels];
          else {
            int shift = postShift + extraBits + zeros + ones + dups;
            int core = v >> shift;
            v = (core << extraBits) | extra(extraBits);
            if (zeros != 0) v <<= zeros;
            else if (ones != 0) v = ((v + 1) << ones) - 1;
            else if (dups != 0) v = ((v + (v & 1)) << dups) - (v & 1);
            extraCrc = extraCrc * 9 + (v & 0xffff) * 3 + (v >>> 16);
            v <<= postShift;
          }
          samples[f * channels + ch] = v;
        }
        for (int b = 0; b < bits / 8; b++)
          pcm[(f * channels + ch) * (bits / 8) + b] = (byte) (v >>> (b * 8));
      }
    remaining -= request;
    delivered += request;
    if (remaining == 0
        && extra != null
        && (floating || extraBits > 0)
        && (flags & 8) == 0
        && extraCrc != extraExpected) throw new IOException("WVX CRC mismatch");
    return pcm;
  }

  public void close() throws IOException {
    if (!closed) {
      closed = true;
      extra = null;
      context = null;
      input.close();
    }
  }
}
