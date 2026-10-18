package indi.mopelotus.musichud.audio.codec.internal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.concentus.OpusMSDecoder;

/** Raw Opus packet core. Container owns delay, timestamps, preroll and EOS trimming. */
public final class OpusPacketDecoder implements Closeable {
  private static final int MAX_PACKET_BYTES = 16 * 1024 * 1024;
  private static final int[][] MAPS = {
    {0},
    {0, 1},
    {0, 2, 1},
    {0, 1, 2, 3},
    {0, 4, 1, 2, 3},
    {0, 4, 1, 2, 3, 5},
    {0, 4, 1, 2, 3, 5, 6},
    {0, 6, 1, 2, 3, 4, 5, 7}
  };
  private static final int[] STREAMS = {1, 1, 2, 2, 3, 4, 4, 5}, COUPLED = {0, 1, 1, 2, 2, 2, 3, 3};
  private final int channels;
  private OpusMSDecoder decoder;
  private volatile boolean closed;
  private final short[] samples;

  public OpusPacketDecoder(byte[] head) throws IOException {
    Objects.requireNonNull(head, "head");
    if (head.length < 19 || head.length > 1024 * 1024)
      throw new IOException("Invalid OpusHead size");
    byte[] magic = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
    for (int i = 0; i < 8; i++)
      if (head[i] != magic[i]) throw new IOException("Missing OpusHead signature");
    int version = head[8] & 255;
    if (version > 15) throw new IOException("Unsupported OpusHead major version");
    channels = head[9] & 255;
    if (channels < 1 || channels > 8) throw new IOException("Unsupported Opus channel count");
    int family = head[18] & 255, streams, coupled, required;
    short[] mapping = new short[channels];
    if (family == 0) {
      if (channels > 2) throw new IOException("Family zero requires mono or stereo");
      streams = 1;
      coupled = channels - 1;
      required = 19;
      for (int i = 0; i < channels; i++) mapping[i] = (short) i;
    } else if (family == 1) {
      required = 21 + channels;
      if (head.length < required) throw new IOException("Truncated Opus mapping");
      streams = head[19] & 255;
      coupled = head[20] & 255;
      if (streams != STREAMS[channels - 1] || coupled != COUPLED[channels - 1])
        throw new IOException("Unsupported nonstandard family-one stream layout");
      for (int i = 0; i < channels; i++) {
        int value = head[21 + i] & 255;
        if (value != MAPS[channels - 1][i])
          throw new IOException("Unsupported nonstandard family-one mapping");
        mapping[i] = (short) value;
      }
    } else throw new IOException("Unsupported Opus mapping family");
    // Version 0/1 have fixed headers. Future minor versions can append fields.
    if (version <= 1 && head.length != required)
      throw new IOException("Unexpected OpusHead trailing data");
    try {
      decoder = OpusMSDecoder.create(48000, channels, streams, coupled, mapping);
      int gain = (short) ((head[16] & 255) | ((head[17] & 255) << 8));
      decoder.setGain(gain);
    } catch (Exception e) {
      throw new IOException("Invalid Opus configuration", e);
    }
    samples = new short[5760 * channels];
  }

  public int channels() {
    return channels;
  }

  /** Output is 48kHz S16LE in standard Vorbis speaker order for family one. */
  public byte[] decodePacket(ByteBuffer packet) throws IOException {
    if (closed) throw new IOException("Opus decoder closed");
    Objects.requireNonNull(packet, "packet");
    try {
      ByteBuffer view = packet.duplicate();
      int size = view.remaining();
      if (size < 1 || size > MAX_PACKET_BYTES) throw new IOException("Invalid Opus packet size");
      byte[] encoded = new byte[size];
      view.get(encoded);
      OpusMSDecoder current = decoder;
      if (current == null || closed) throw new IOException("Opus decoder closed");
      int frames = current.decodeMultistream(encoded, 0, size, samples, 0, 5760, 0);
      if (frames <= 0 || frames > 5760)
        throw new IOException("Invalid Opus packet result: " + frames);
      byte[] out = new byte[frames * channels * 2];
      for (int i = 0, p = 0; i < frames * channels; i++) {
        int sample = samples[i];
        out[p++] = (byte) sample;
        out[p++] = (byte) (sample >>> 8);
      }
      if (closed) throw new IOException("Opus decoder closed");
      return out;
    } catch (IOException e) {
      close();
      throw e;
    } catch (RuntimeException e) {
      close();
      throw new IOException("Malformed Opus packet", e);
    }
  }

  @Override
  public void close() {
    closed = true;
    decoder = null;
  }
}
