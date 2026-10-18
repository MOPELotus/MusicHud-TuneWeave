package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.aac.TsAdtsPacketSource;
import indi.mopelotus.musichud.audio.container.input.HlsTsPesSource;
import java.io.*;
import java.util.*;

/**
 * HLS TS AAC packets with exact programme-relative timestamps, retaining genuine epoch identity.
 */
public final class HlsTsPacketSource implements PacketSource {
  private final NormalizedPacketSource normalized;

  public HlsTsPacketSource(HlsTsPesSource owned) throws IOException {
    Objects.requireNonNull(owned);
    TsAdtsPacketSource adts =
        new TsAdtsPacketSource(
            new TsAdtsPacketSource.PesInput() {
              public TsAdtsPacketSource.Pes next() throws IOException {
                var p = owned.next();
                return p == null
                    ? null
                    : new TsAdtsPacketSource.Pes(
                        p.elementary(),
                        p.pts90k(),
                        p.discontinuity(),
                        p.streamType(),
                        p.hlsDiscontinuitySequence());
              }

              public void close() {
                owned.close();
              }
            });
    normalized = new NormalizedPacketSource(adts, adts::timelineEpoch, new Time(1, 90000));
  }

  public Track track() {
    return normalized.track();
  }

  public Packet next() throws IOException {
    return normalized.next();
  }

  public Time seekMedia(Time target) throws IOException {
    return normalized.seekMedia(target);
  }

  public void close() throws IOException {
    normalized.close();
  }
}
