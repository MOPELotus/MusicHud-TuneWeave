package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.io.IOException;

/**
 * Caps a fresh decoder's recovery probe before the target; successful playback releases the cap.
 */
public final class AacRecoveryWindow implements PacketSource {
  public static final class Unavailable extends IOException {
    public Unavailable() {
      super("No AAC recovery anchor before presentation warmup boundary");
    }
  }

  private final PacketSource owned;
  private final Time latestReady;
  private int packets;
  private boolean released;

  public AacRecoveryWindow(PacketSource owned, Time latestReady) {
    this.owned = java.util.Objects.requireNonNull(owned);
    this.latestReady = java.util.Objects.requireNonNull(latestReady);
  }

  public void release() {
    released = true;
  }

  public Track track() {
    return owned.track();
  }

  public Packet next() throws IOException {
    Packet packet = owned.next();
    if (!released
        && (packet == null
            || ++packets > 4096
            || packet.presentationTime() == null
            || packet.presentationTime().compareValue(latestReady) > 0)) throw new Unavailable();
    return packet;
  }

  public Time seekMedia(Time target) throws IOException {
    return owned.seekMedia(target);
  }

  public void close() throws IOException {
    owned.close();
  }
}
