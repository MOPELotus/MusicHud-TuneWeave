package indi.mopelotus.musichud.audio.container;

import java.io.*;
import java.nio.*;

/** Bounded MPEG-4 descriptors, preserving decoder configuration verbatim. */
public final class RawEsds {
  public record Description(int objectType, byte[] decoderSpecificInfo) {
    public Description {
      decoderSpecificInfo = decoderSpecificInfo.clone();
    }

    @Override
    public byte[] decoderSpecificInfo() {
      return decoderSpecificInfo.clone();
    }
  }

  private static final class State {
    int objectType = -1;
    byte[] config;
  }

  public static Description description(ByteBuffer input) throws IOException {
    State state = new State();
    walk(input.duplicate(), 0, state);
    if (state.objectType < 0) throw new IOException("Missing DecoderConfigDescriptor");
    return new Description(state.objectType, state.config == null ? new byte[0] : state.config);
  }

  public static byte[] audioSpecificConfig(ByteBuffer input) throws IOException {
    byte[] config = description(input).decoderSpecificInfo();
    if (config.length == 0) throw new IOException("No DecoderSpecificInfo");
    return config;
  }

  private static void walk(ByteBuffer b, int depth, State state) throws IOException {
    if (depth > 4) throw new IOException("Descriptor nesting exceeds bound");
    while (b.hasRemaining()) {
      int tag = u8(b), length = 0, n = 0, x;
      do {
        if (++n > 4) throw new IOException("Descriptor length exceeds four bytes");
        x = u8(b);
        length = (length << 7) | (x & 127);
      } while ((x & 128) != 0);
      if (length > b.remaining()) throw new EOFException("Truncated descriptor payload");
      ByteBuffer payload = b.slice();
      payload.limit(length);
      b.position(b.position() + length);
      if (tag == 5) {
        if (length > 65536 || state.config != null)
          throw new IOException("Invalid/duplicate DecoderSpecificInfo");
        state.config = new byte[length];
        payload.get(state.config);
      } else if (tag == 3) {
        skip(payload, 2);
        int flags = u8(payload);
        if ((flags & 128) != 0) skip(payload, 2);
        if ((flags & 64) != 0) skip(payload, u8(payload));
        if ((flags & 32) != 0) skip(payload, 2);
        walk(payload, depth + 1, state);
      } else if (tag == 4) {
        if (state.objectType >= 0) throw new IOException("Duplicate DecoderConfigDescriptor");
        state.objectType = u8(payload);
        skip(payload, 12);
        walk(payload, depth + 1, state);
      }
    }
  }

  private static int u8(ByteBuffer b) throws EOFException {
    if (!b.hasRemaining()) throw new EOFException("Truncated descriptor");
    return b.get() & 255;
  }

  private static void skip(ByteBuffer b, int n) throws EOFException {
    if (n < 0 || n > b.remaining()) throw new EOFException("Truncated descriptor fields");
    b.position(b.position() + n);
  }
}
