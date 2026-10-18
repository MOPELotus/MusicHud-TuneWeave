package indi.mopelotus.musichud.audio.codec;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Raw media PCM from one compressed packet, retaining all original exact timing. No presentation
 * edits, encoder priming, container delay or discard fields are applied here.
 */
public record DecodedPacket(PacketSource.Packet source, PcmFormat format, ByteBuffer pcm) {
  public DecodedPacket {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(format, "format");
    ByteBuffer input = Objects.requireNonNull(pcm, "pcm").duplicate();
    if (input.remaining() > 16 * 1024 * 1024 || input.remaining() % format.frameBytes() != 0)
      throw new IllegalArgumentException("Invalid decoded PCM frame extent");
    ByteBuffer copy = ByteBuffer.allocate(input.remaining());
    copy.put(input).flip();
    pcm = copy.asReadOnlyBuffer();
  }

  @Override
  public ByteBuffer pcm() {
    return pcm.asReadOnlyBuffer();
  }

  public int rawFrames() {
    return pcm.remaining() / format.frameBytes();
  }
}
