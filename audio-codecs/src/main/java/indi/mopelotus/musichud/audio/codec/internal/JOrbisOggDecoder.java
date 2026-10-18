package indi.mopelotus.musichud.audio.codec.internal;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.*;
import java.io.*;
import java.util.*;
import org.concentus.*;

/**
 * Synchronous codec adapter: synchronous, bounded Ogg packets; up to eight channels; Vorbis channel
 * order. Owns input.
 */
public final class JOrbisOggDecoder implements Closeable {
  private final InputStream input;
  private final ArrayDeque<Pkt> queue = new ArrayDeque<>();
  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
  private volatile boolean closed;
  private boolean eos;
  private int serial, sequence;
  private boolean started;
  private long packetNumber, decodedFrames, vorbisFrames;
  private int preSkip;
  private Info info;
  private DspState dsp;
  private Block block;
  private OpusMSDecoder opus;
  public final int channels, sampleRate;

  record Pkt(
      byte[] bytes, long granule, boolean first, boolean last, long finalGranule, long number) {}

  public JOrbisOggDecoder(InputStream in) throws IOException {
    input = Objects.requireNonNull(in);
    try {
      Pkt first = next();
      if (first == null) throw new IOException("Empty Ogg");
      if (starts(first.bytes, "OpusHead")) {
        byte[] h = first.bytes;
        if (h.length < 19 || (h[8] & 255) > 15)
          throw new IOException("Unsupported Opus mapping/version");
        channels = h[9] & 255;
        sampleRate = 48000;
        checkChannels();
        preSkip = u16(h, 10);
        int family = h[18] & 255;
        int streams, coupled;
        short[] mapping = new short[channels];
        if (family == 0) {
          if (channels > 2) throw new IOException("Invalid family-zero channels");
          streams = 1;
          coupled = channels - 1;
          for (int i = 0; i < channels; i++) mapping[i] = (short) i;
        } else if (family == 1) {
          if (h.length < 21 + channels) throw new IOException("Truncated mapping");
          streams = h[19] & 255;
          coupled = h[20] & 255;
          for (int i = 0; i < channels; i++) mapping[i] = (short) (h[21 + i] & 255);
        } else throw new IOException("Unsupported Opus channel mapping family");
        opus = OpusMSDecoder.create(sampleRate, channels, streams, coupled, mapping);
        opus.setGain((short) u16(h, 16));
        Pkt tags = next();
        if (tags == null || !starts(tags.bytes, "OpusTags"))
          throw new IOException("Missing OpusTags");
      } else {
        info = new Info();
        info.init();
        Comment comment = new Comment();
        comment.init();
        Pkt p = first;
        for (int i = 0; i < 3; i++) {
          if (p == null || info.synthesis_headerin(comment, packet(p)) < 0)
            throw new IOException("Invalid Vorbis header");
          if (i < 2) p = next();
        }
        channels = info.channels;
        sampleRate = info.rate;
        checkChannels();
        if (sampleRate < 1 || sampleRate > 384000) throw new IOException("Invalid rate");
        dsp = new DspState();
        if (dsp.synthesis_init(info) != 0) throw new IOException("Vorbis init failed");
        block = new Block(dsp);
      }
    } catch (Exception e) {
      try {
        close();
      } catch (Exception ignored) {
      }
      if (e instanceof IOException x) throw x;
      throw new IOException("Decoder initialization failed", e);
    }
  }

  private void checkChannels() throws IOException {
    if (channels < 1 || channels > 8)
      throw new IOException("Channel count exceeds supported bound");
  }

  private static boolean starts(byte[] b, String s) {
    byte[] x = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    return b.length >= x.length && Arrays.equals(Arrays.copyOf(b, x.length), x);
  }

  private static int u16(byte[] b, int o) {
    return (b[o] & 255) | ((b[o + 1] & 255) << 8);
  }

  private static long le(byte[] b, int o, int n) {
    long v = 0;
    for (int i = 0; i < n; i++) v |= (long) (b[o + i] & 255) << (8 * i);
    return v;
  }

  private byte[] exact(int n) throws IOException {
    byte[] b = input.readNBytes(n);
    if (b.length != n) throw new EOFException("Truncated Ogg page");
    return b;
  }

  private static final int[] CRC_TABLE = crcTable();

  private static int[] crcTable() {
    int[] table = new int[256];
    for (int n = 0; n < 256; n++) {
      int value = n << 24;
      for (int i = 0; i < 8; i++) value = (value << 1) ^ ((value < 0) ? 0x04c11db7 : 0);
      table[n] = value;
    }
    return table;
  }

  private static int crc(byte[] b, int crc) {
    for (byte v : b) crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (v & 255)) & 255];
    return crc;
  }

  private Pkt next() throws IOException {
    while (queue.isEmpty()) {
      if (closed) throw new IOException("Decoder closed");
      if (eos) {
        if (partial.size() != 0) throw new IOException("Incomplete final Ogg packet");
        return null;
      }
      byte[] h = exact(27);
      if (!starts(h, "OggS") || h[4] != 0) throw new IOException("Invalid Ogg page");
      int flags = h[5] & 255;
      if ((flags & ~7) != 0) throw new IOException("Reserved Ogg flags");
      int sn = (int) le(h, 14, 4), seq = (int) le(h, 18, 4);
      if (!started) {
        if ((flags & 2) == 0 || seq != 0) throw new IOException("Missing Ogg BOS");
        started = true;
        serial = sn;
        sequence = seq;
      } else if (sn != serial || seq != ++sequence || (flags & 2) != 0)
        throw new IOException("Chaining/multiplexing or page discontinuity");
      if (((flags & 1) != 0) != (partial.size() != 0))
        throw new IOException("Invalid continued packet");
      byte[] lace = exact(h[26] & 255);
      int total = 0;
      for (byte l : lace) total += l & 255;
      byte[] body = exact(total);
      int expected = (int) le(h, 22, 4);
      Arrays.fill(h, 22, 26, (byte) 0);
      if (crc(body, crc(lace, crc(h, 0))) != expected)
        throw new IOException("Ogg checksum mismatch");
      long granule = le(h, 6, 8);
      eos = (flags & 4) != 0;
      if (eos && granule < 0) throw new IOException("Invalid final granule");
      int last = -1;
      for (int i = 0; i < lace.length; i++) if ((lace[i] & 255) < 255) last = i;
      int pos = 0;
      for (int i = 0; i < lace.length; i++) {
        int len = lace[i] & 255;
        if (partial.size() + len > 16 * 1024 * 1024)
          throw new IOException("Ogg packet exceeds bound");
        partial.write(body, pos, len);
        pos += len;
        if (len < 255) {
          byte[] data = partial.toByteArray();
          partial.reset();
          queue.add(
              new Pkt(
                  data,
                  i == last ? granule : -1,
                  packetNumber == 0,
                  eos && i == last,
                  eos ? granule : -1,
                  packetNumber++));
        }
      }
    }
    return queue.remove();
  }

  private static Packet packet(Pkt p) {
    Packet v = new Packet();
    v.packet_base = p.bytes;
    v.bytes = p.bytes.length;
    v.b_o_s = p.first ? 1 : 0;
    v.e_o_s = p.last ? 1 : 0;
    v.granulepos = p.granule;
    v.packetno = p.number;
    return v;
  }

  /** Next interleaved signed little-endian 16-bit PCM; null at logical EOF. */
  public byte[] readChunk() throws IOException {
    if (closed) throw new IOException("Decoder closed");
    try {
      for (; ; ) {
        Pkt p = next();
        if (p == null) return null;
        if (opus != null) {
          short[] pcm = new short[5760 * channels];
          int frames = opus.decodeMultistream(p.bytes, 0, p.bytes.length, pcm, 0, 5760, 0);
          long start = decodedFrames;
          decodedFrames += frames;
          int lo = (int) Math.max(0, Math.min(frames, preSkip - start));
          int hi = frames;
          if (p.finalGranule >= 0) hi = (int) Math.max(0, Math.min(frames, p.finalGranule - start));
          if (hi <= lo) continue;
          byte[] out = new byte[(hi - lo) * channels * 2];
          int at = 0;
          for (int i = lo * channels; i < hi * channels; i++) {
            out[at++] = (byte) pcm[i];
            out[at++] = (byte) (pcm[i] >>> 8);
          }
          if (closed) throw new IOException("Decoder closed");
          return out;
        }
        if (block.synthesis(packet(p)) != 0 || dsp.synthesis_blockin(block) != 0)
          throw new IOException("Corrupt Vorbis audio packet");
        float[][][] pcmHolder = new float[1][][];
        int[] offsets = new int[channels];
        int samples = dsp.synthesis_pcmout(pcmHolder, offsets);
        if (samples <= 0) continue;
        int outputSamples = samples;
        if (p.finalGranule >= 0)
          outputSamples = (int) Math.max(0, Math.min(samples, p.finalGranule - vorbisFrames));
        byte[] out = new byte[outputSamples * channels * 2];
        int at = 0;
        for (int f = 0; f < outputSamples; f++)
          for (int c = 0; c < channels; c++) {
            int v = (int) (pcmHolder[0][c][offsets[c] + f] * 32768.0);
            v = Math.max(-32768, Math.min(32767, v));
            out[at++] = (byte) v;
            out[at++] = (byte) (v >>> 8);
          }
        vorbisFrames += outputSamples;
        if (dsp.synthesis_read(samples) != 0) throw new IOException("Vorbis consume failed");
        if (outputSamples == 0) continue;
        if (closed) throw new IOException("Decoder closed");
        return out;
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Invalid Ogg audio", e);
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      input.close();
    } finally {
      queue.clear();
      partial.reset();
      if (block != null) block.clear();
      if (dsp != null) dsp.clear();
      if (info != null) info.clear();
    }
  }
}
