package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.input.HlsPlaybackInput;
import indi.mopelotus.musichud.audio.container.input.HlsTsPesSource;
import java.io.*;
import java.util.*;

/** Detects TS or fragmented MP4 from the already fetched first HLS segment, with no second GET. */
public final class HlsPacketSource implements PacketSource {
  private final PacketSource delegate;

  public HlsPacketSource(HlsPlaybackInput owned) throws IOException {
    Objects.requireNonNull(owned);
    try {
      var first = owned.next();
      if (first == null) throw new EOFException("Empty HLS playlist");
      delegate =
          first.initialization() == null
              ? new HlsTsPacketSource(new HlsTsPesSource(owned, first))
              : new HlsFmp4PacketSource(owned, first);
    } catch (Throwable failure) {
      owned.close();
      throw failure;
    }
  }

  public Track track() {
    return delegate.track();
  }

  public Packet next() throws IOException {
    return delegate.next();
  }

  public Time seekMedia(Time target) throws IOException {
    return delegate.seekMedia(target);
  }

  public void close() throws IOException {
    delegate.close();
  }
}
