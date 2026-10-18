package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.SpeakerOrder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded explicit-speaker fold using the existing PCM conversion matrix, with no new DSP. */
final class PacketPcmFold {
  private final PcmFormat format;
  private final SpeakerOrder order;
  private final PcmOutput output;
  private final boolean downmixed;

  PacketPcmFold(PcmFormat format) throws IOException {
    this.format = format;
    try {
      order = SpeakerOrder.forFormat(format);
      output = new PcmOutput(format.channels(), 32, format.sampleRate(), true, order.mask(), false);
      downmixed =
          !(format.channels() == 1 && order.mask() == 4
              || format.channels() == 2 && order.mask() == 3);
    } catch (IllegalArgumentException error) {
      throw new IOException("Explicit supported decoded speaker order required", error);
    }
  }

  boolean hasDownmixedChannels() {
    return downmixed;
  }

  float[] convert(ByteBuffer raw, int frames) throws IOException {
    if (frames < 0 || frames > 1024 || raw.remaining() < (long) frames * format.frameBytes()) {
      throw new IOException("Invalid bounded PCM fold extent");
    }
    byte[] original = new byte[frames * format.frameBytes()];
    raw.get(original);
    byte[] canonical = order.canonical(original, format.encoding().bytes);
    ByteBuffer source = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
    ByteBuffer wide =
        ByteBuffer.allocate(frames * format.channels() * 4).order(ByteOrder.LITTLE_ENDIAN);
    boolean floating = format.encoding() == PcmFormat.Encoding.F32LE;
    while (source.hasRemaining()) {
      switch (format.encoding()) {
        case F32LE -> {
          float value = source.getFloat();
          if (!Float.isFinite(value)) throw new IOException("Non-finite packet PCM");
          wide.putFloat(value);
        }
        case S8 -> wide.putInt(source.get() << 24);
        case S16LE -> wide.putInt(source.getShort() << 16);
        case S24LE -> {
          int value =
              (source.get() & 255) | ((source.get() & 255) << 8) | ((source.get() & 255) << 16);
          wide.putInt(value << 8);
        }
        case S32LE -> wide.putInt(source.getInt());
      }
    }
    ByteBuffer folded =
        ByteBuffer.wrap(output.convert(wide.array(), floating)).order(ByteOrder.LITTLE_ENDIAN);
    float[] stereo = new float[frames * 2];
    int channels = output.frameSize() / 4;
    for (int i = 0; i < frames; i++) {
      float left = folded.getFloat();
      stereo[i * 2] = left;
      stereo[i * 2 + 1] = channels == 1 ? left : folded.getFloat();
    }
    return stereo;
  }
}
