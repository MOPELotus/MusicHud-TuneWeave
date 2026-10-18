package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.container.info.AudioTrackInfoProvider;
import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.*;

/** Owned JDK channel adapter for the container parser; no transport or provider policy. */
public final class ChannelSeekableInput extends SeekableInputStream {
  private final SeekableByteChannel channel;
  private final byte[] one = new byte[1];
  private volatile long position;

  public ChannelSeekableInput(SeekableByteChannel owned) throws IOException {
    super(-1, 8192);
    channel = Objects.requireNonNull(owned);
    try {
      contentLength = channel.size();
      position = channel.position();
    } catch (Throwable error) {
      try {
        channel.close();
      } catch (IOException close) {
        error.addSuppressed(close);
      }
      throw error;
    }
  }

  @Override
  public int read() throws IOException {
    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
  }

  @Override
  public int read(byte[] bytes, int offset, int count) throws IOException {
    Objects.checkFromIndexSize(offset, count, bytes.length);
    if (count == 0) return 0;
    int read = channel.read(ByteBuffer.wrap(bytes, offset, count));
    if (read > 0) position = Math.addExact(position, read);
    return read;
  }

  @Override
  public long getPosition() {
    return position;
  }

  @Override
  protected void seekHard(long next) throws IOException {
    if (next < 0 || next > contentLength) throw new EOFException("Invalid container byte offset");
    channel.position(next);
    position = next;
  }

  @Override
  public boolean canSeekHard() {
    return true;
  }

  @Override
  public List<AudioTrackInfoProvider> getTrackInfoProviders() {
    return List.of();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
