package indi.mopelotus.musichud.audio.codec;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/**
 * One codec configuration; all methods, including close, have one caller. Owns only codec state,
 * never a PacketSource or network input. The caller owns packet retrieval, cancellation and exact
 * presentation mapping.
 */
public interface PacketDecoder extends Closeable {
  /** The known output format. AAC implicit SBR may establish it only after the first PCM output. */
  Optional<PcmFormat> format();

  /**
   * Decodes raw media PCM, preserving source timing. Null means the packet produced no PCM. A
   * configuration change or discontinuity after decoding starts requires a fresh decoder. Failure
   * is terminal; it is not safe to feed another packet after an exception.
   */
  DecodedPacket decode(PacketSource.Packet packet) throws IOException;

  /**
   * Drains additional PCM at clean EOF. The current cores produce all output during decode and
   * return null. This does not remove encoder priming or invent trailing silence.
   */
  DecodedPacket finish() throws IOException;

  @Override
  void close() throws IOException;
}
