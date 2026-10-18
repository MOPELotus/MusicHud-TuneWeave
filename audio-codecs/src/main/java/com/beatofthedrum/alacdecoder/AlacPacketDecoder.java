package com.beatofthedrum.alacdecoder;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Bounded packet adapter over original BSD ALAC DSP. No SPI or MP4 parser. */
public final class AlacPacketDecoder implements AutoCloseable {
  public record Format(int sampleRate, int channels, int bits, int maxFrames) {}

  private AlacFile decoder;
  private final Format format;
  private int[] scratch;

  /**
   * Accept a complete 36-byte alac child box, 28-byte box payload, or raw 24-byte
   * ALACSpecificConfig.
   */
  public AlacPacketDecoder(byte[] config) throws IOException {
    int start;
    if (config.length == 36) {
      if (ByteBuffer.wrap(config).getInt() != 36
          || config[4] != 'a'
          || config[5] != 'l'
          || config[6] != 'a'
          || config[7] != 'c') throw new IOException("Invalid ALAC box");
      start = 12;
    } else if (config.length == 28) start = 4;
    else if (config.length == 24) start = 0;
    else throw new IOException("Invalid ALAC config size");
    ByteBuffer c = ByteBuffer.wrap(config, start, 24).slice().order(ByteOrder.BIG_ENDIAN);
    int frames = c.getInt(0),
        version = c.get(4) & 255,
        bits = c.get(5) & 255,
        ch = c.get(9) & 255,
        rate = c.getInt(20);
    if (version != 0
        || frames < 1
        || frames > 16384
        || (bits != 16 && bits != 20 && bits != 24)
        || (ch != 1 && ch != 2)
        || rate < 1
        || rate > 768000) throw new IOException("Unsupported ALAC config");
    format = new Format(rate, ch, bits, frames);
    decoder = AlacDecodeUtils.create_alac(bits, ch);
    int[] legacy = new int[48];
    for (int i = 0; i < 24; i++) legacy[24 + i] = config[start + i] & 255;
    AlacDecodeUtils.alac_set_info(
        decoder,
        legacy); // Original core writes one silent partner after each mono sample, including the
    // final one.
    // Six guard ints preserve that bounded convention without forking the DSP.
    scratch = new int[frames * ch * ((bits + 7) / 8) + 6];
  }

  public Format format() {
    return format;
  }

  public byte[] decode(ByteBuffer packet) throws IOException {
    if (decoder == null) throw new IOException("ALAC decoder closed");
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("ALAC interrupted");
    ByteBuffer copy = packet.slice();
    int size = copy.remaining();
    if (size < 3 || size > 1048576) throw new IOException("Invalid ALAC packet size");
    byte[] data = new byte[size + 3];
    copy.get(data, 0, size);
    int element = (data[0] & 255) >>> 5;
    if (element != format.channels() - 1) throw new IOException("Unexpected ALAC channel element");
    // 3-bit element, 4-bit tag, 12 reserved bits, 1-bit size flag, 2-bit uncompressed bytes, 1-bit
    // raw flag.
    boolean hasSize = (data[2] & 0x10) != 0;
    int expected = format.maxFrames();
    if (hasSize) {
      if (size < 7) throw new EOFException("Truncated ALAC sample count");
      long count = 0;
      for (int bit = 23; bit < 55; bit++)
        count = (count << 1) | ((data[bit / 8] >>> (7 - bit % 8)) & 1);
      if (count < 1 || count > format.maxFrames())
        throw new IOException("Invalid ALAC frame sample count");
      expected = (int) count;
    }
    try {
      int n = AlacDecodeUtils.decode_frame(decoder, data, scratch, scratch.length);
      int bytes = expected * format.channels() * ((format.bits() + 7) / 8);
      if (n != bytes || decoder.ibIdx * 8 + decoder.input_buffer_bitaccumulator > size * 8)
        throw new IOException("Invalid or truncated ALAC frame");
      byte[] out = new byte[n];
      if (format.bits() == 24 || format.bits() == 20) {
        for (int i = 0; i < n; i++) out[i] = (byte) scratch[i];
      } else {
        for (int i = 0; i < n / 2; i++) {
          out[2 * i] = (byte) scratch[i];
          out[2 * i + 1] = (byte) (scratch[i] >>> 8);
        }
      }
      if (format.bits() == 20) {
        // API: signed20-bit samples left-aligned in little-endian24-bit containers, matching Apple.
        for (int i = 0; i < out.length; i += 3) {
          int value = (out[i] & 255) | ((out[i + 1] & 255) << 8) | (out[i + 2] << 16);
          value <<= 4;
          out[i] = (byte) value;
          out[i + 1] = (byte) (value >>> 8);
          out[i + 2] = (byte) (value >>> 16);
        }
      }
      return out;
    } catch (RuntimeException e) {
      throw new IOException("Malformed ALAC packet", e);
    }
  }

  public void close() {
    decoder = null;
    scratch = null;
  }
}
