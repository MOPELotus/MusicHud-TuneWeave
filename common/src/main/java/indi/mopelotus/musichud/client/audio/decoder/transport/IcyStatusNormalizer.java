package indi.mopelotus.musichud.client.audio.decoder.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Adapts only the legacy ICY status token; Netty still parses the entire HTTP response. */
final class IcyStatusNormalizer extends ByteToMessageDecoder {
  private boolean decided;

  @Override
  protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
    if (!decided) {
      if (input.readableBytes() < 4) return;
      int start = input.readerIndex();
      boolean icy =
          input.getByte(start) == 'I'
              && input.getByte(start + 1) == 'C'
              && input.getByte(start + 2) == 'Y'
              && input.getByte(start + 3) == ' ';
      if (icy) {
        if (input.readableBytes() < 8) return;
        // Do not turn ICYS, arbitrary versions, or a malformed status token into HTTP.
        boolean digits = true;
        for (int i = 4; i < 7; i++)
          digits &= input.getByte(start + i) >= '0' && input.getByte(start + i) <= '9';
        byte separator = input.getByte(start + 7);
        if (digits && (separator == ' ' || separator == '\r' || separator == '\n')) {
          ByteBuf normalized = context.alloc().buffer(input.readableBytes() + 5);
          normalized.writeCharSequence("HTTP/1.0 ", StandardCharsets.US_ASCII);
          input.skipBytes(4);
          normalized.writeBytes(input);
          output.add(normalized);
          decided = true;
          return;
        }
      }
      decided = true;
    }
    output.add(input.readRetainedSlice(input.readableBytes()));
  }
}
