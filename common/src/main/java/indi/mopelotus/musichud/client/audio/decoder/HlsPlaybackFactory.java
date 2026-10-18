package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.input.HlsPlaybackInput;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Each replay owns its manifest/segment requests; only the application's transport is shared. */
final class HlsPlaybackFactory implements PresentedDecoder.Factory {
  private final URI uri;
  private final boolean publicOnly;
  private final HlsPlaybackInput.Fetcher fetcher;
  private final AtomicReference<PresentedDecoder.Attempt> primed = new AtomicReference<>();
  private PacketSource.Track track;

  HlsPlaybackFactory(URI uri, boolean publicOnly, MediaHeaderScope scope, byte[] initialManifest)
      throws IOException {
    this.uri = uri;
    this.publicOnly = publicOnly;
    var network = new TransportHlsFetcher(PcmAudioInput.sharedTransport(), scope::forUri);
    AtomicReference<byte[]> seed = new AtomicReference<>(initialManifest.clone());
    fetcher =
        (target, range, policy) -> {
          byte[] initial = target.equals(uri) && range == null ? seed.getAndSet(null) : null;
          if (initial == null) return network.request(target, range, policy);
          return new HlsPlaybackInput.Request() {
            private boolean cancelled;

            public synchronized HlsPlaybackInput.Resource open() throws IOException {
              if (cancelled) throw new IOException("HLS manifest cancelled");
              return new HlsPlaybackInput.Resource() {
                private final InputStream body = new ByteArrayInputStream(initial);

                public URI finalUri() {
                  return uri;
                }

                public InputStream body() {
                  return body;
                }

                public void close() throws IOException {
                  body.close();
                }
              };
            }

            public synchronized void close() {
              cancelled = true;
            }
          };
        };
  }

  PacketMediaDecoder prime() throws IOException {
    var attempt = freshAttempt();
    try {
      PacketMediaDecoder decoder = (PacketMediaDecoder) attempt.open();
      track = decoder.track();
      primed.set(attempt);
      return decoder;
    } catch (Throwable error) {
      attempt.cancel();
      throw error;
    }
  }

  public PresentedDecoder.Attempt attempt() {
    var initial = primed.getAndSet(null);
    return initial == null ? freshAttempt() : initial;
  }

  private PresentedDecoder.Attempt freshAttempt() {
    return new PresentedDecoder.Attempt() {
      private HlsPlaybackInput input;
      private PacketMediaDecoder decoder;
      private boolean cancelled;

      public indi.mopelotus.musichud.audio.codec.PcmDecoder open() throws IOException {
        synchronized (this) {
          if (cancelled) throw new IOException("HLS replay cancelled");
          if (decoder != null) return decoder;
          input = new HlsPlaybackInput(uri, publicOnly, null, fetcher);
        }
        var source = new HlsPacketSource(input);
        if (track != null && !track.equals(source.track())) {
          source.close();
          throw new IOException("HLS replay editing changed");
        }
        PacketMediaDecoder opened = new PacketMediaDecoder(source);
        synchronized (this) {
          if (!cancelled) {
            decoder = opened;
            return opened;
          }
        }
        opened.close();
        throw new IOException("Late HLS replay");
      }

      public void cancel() {
        HlsPlaybackInput stream;
        PacketMediaDecoder output;
        synchronized (this) {
          if (cancelled) return;
          cancelled = true;
          stream = input;
          output = decoder;
        }
        if (stream != null) stream.close();
        if (output != null)
          try {
            output.close();
          } catch (IOException ignored) {
          }
      }
    };
  }
}
