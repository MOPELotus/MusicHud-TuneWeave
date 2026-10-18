package indi.mopelotus.musichud.audio.codec.internal;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.*;
import java.io.*;
import java.nio.ByteBuffer;

/**
 * Synchronous Vorbis packet core. PCM retains codec output; presentation trims belong to caller.
 * Not thread-safe for decode/drain; independent instances share no decoder state. Close is
 * idempotent.
 */
public final class VorbisPacketDecoder implements Closeable {
  public static final int MAX_CONFIG_BYTES = 1024 * 1024, MAX_PACKET_BYTES = 16 * 1024 * 1024;
  private Info info;
  private DspState dsp;
  private Block block;
  private boolean closed;
  private long packetNumber = 3;
  private final int channels, sampleRate;

  public VorbisPacketDecoder(byte[] xiphLacedHeaders) throws IOException {
    try {
      byte[][] headers = splitHeaders(xiphLacedHeaders);
      info = new Info();
      info.init();
      Comment comment = new Comment();
      comment.init();
      for (int i = 0; i < 3; i++)
        if (info.synthesis_headerin(comment, packet(headers[i], i, i == 0)) < 0)
          throw new IOException("Invalid Vorbis header " + i);
      channels = info.channels;
      sampleRate = info.rate;
      if (channels < 1 || channels > 8 || sampleRate < 1 || sampleRate > 384000)
        throw new IOException("Unsupported Vorbis channel count/rate");
      dsp = new DspState();
      if (dsp.synthesis_init(info) != 0) throw new IOException("Vorbis initialization failed");
      block = new Block(dsp);
    } catch (IOException | RuntimeException e) {
      close();
      if (e instanceof IOException x) throw x;
      throw new IOException("Invalid Vorbis configuration", e);
    }
  }

  public int channels() {
    return channels;
  }

  public int sampleRate() {
    return sampleRate;
  }

  /** Xiph lacing uses two lengths; the third header occupies all remaining bytes. */
  public static byte[][] splitHeaders(byte[] config) throws IOException {
    if (config == null || config.length < 4 || config.length > MAX_CONFIG_BYTES || config[0] != 2)
      throw new IOException("Invalid Vorbis Xiph header configuration");
    int pos = 1;
    int[] lengths = new int[3];
    for (int i = 0; i < 2; i++) {
      int n;
      do {
        if (pos >= config.length) throw new EOFException("Truncated Xiph length");
        n = config[pos++] & 255;
        lengths[i] += n;
        if (lengths[i] > MAX_CONFIG_BYTES) throw new IOException("Xiph length exceeds bound");
      } while (n == 255);
    }
    lengths[2] = config.length - pos - lengths[0] - lengths[1];
    for (int n : lengths) if (n <= 0) throw new IOException("Empty or overlapping Vorbis header");
    byte[][] result = new byte[3][];
    for (int i = 0; i < 3; i++) {
      if (lengths[i] > config.length - pos) throw new EOFException("Truncated Vorbis header");
      result[i] = java.util.Arrays.copyOfRange(config, pos, pos + lengths[i]);
      pos += lengths[i];
    }
    return result;
  }

  private static Packet packet(byte[] bytes, long number, boolean bos) {
    Packet p = new Packet();
    p.packet_base = bytes;
    p.bytes = bytes.length;
    p.packetno = number;
    p.b_o_s = bos ? 1 : 0;
    p.granulepos = -1;
    return p;
  }

  /**
   * Consumes exactly one compressed packet. Output frame count is variable and may be zero. Does
   * not mutate the caller's ByteBuffer position or retain the caller's storage.
   */
  public synchronized byte[] decodePacket(ByteBuffer encoded) throws IOException {
    ensureOpen();
    if (encoded == null || encoded.remaining() == 0 || encoded.remaining() > MAX_PACKET_BYTES) {
      close();
      throw new IOException("Invalid Vorbis packet length");
    }
    byte[] bytes = new byte[encoded.remaining()];
    encoded.duplicate().get(bytes);
    try {
      if (block.synthesis(packet(bytes, packetNumber++, false)) != 0
          || dsp.synthesis_blockin(block) != 0) throw new IOException("Corrupt Vorbis packet");
      return drainInternal();
    } catch (IOException | RuntimeException e) {
      close();
      if (e instanceof IOException x) throw x;
      throw new IOException("Corrupt Vorbis packet", e);
    }
  }

  /**
   * Drains only PCM already produced by complete packets; does not invent an EOS packet or trim.
   */
  public synchronized byte[] drain() throws IOException {
    ensureOpen();
    return drainInternal();
  }

  private byte[] drainInternal() throws IOException {
    float[][][] holder = new float[1][][];
    int[] offsets = new int[channels];
    int frames = dsp.synthesis_pcmout(holder, offsets);
    if (frames < 0 || frames > 8192) throw new IOException("Vorbis output frame bound exceeded");
    byte[] out = new byte[frames * channels * 2];
    int at = 0;
    for (int f = 0; f < frames; f++)
      for (int c = 0; c < channels; c++) {
        int value = (int) (holder[0][c][offsets[c] + f] * 32768.0);
        value = Math.max(-32768, Math.min(32767, value));
        out[at++] = (byte) value;
        out[at++] = (byte) (value >>> 8);
      }
    if (dsp.synthesis_read(frames) != 0) throw new IOException("Vorbis consume failed");
    return out;
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("Decoder closed");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      if (block != null) block.clear();
    } catch (RuntimeException ignored) {
    }
    try {
      if (dsp != null) dsp.clear();
    } catch (RuntimeException ignored) {
    }
    try {
      if (info != null) info.clear();
    } catch (RuntimeException ignored) {
    }
    block = null;
    dsp = null;
    info = null;
  }
}
