package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.*;
import java.io.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * First playback remains incremental; random access prepares one entity-bound compressed source.
 */
final class OggPlaybackFactory implements PresentedDecoder.Factory {
  private final JavaAudioResource resource;
  private final PresentedDecoder.Factory sequential;
  private final int targetRate;
  private final AtomicReference<JavaAudioResource.StableSession> snapshot = new AtomicReference<>();
  private volatile boolean declined;

  OggPlaybackFactory(
      JavaAudioResource resource, PresentedDecoder.Factory sequential, int targetRate) {
    this.resource = resource;
    this.sequential = sequential;
    this.targetRate = targetRate;
  }

  public boolean supportsFastSeek() {
    return !declined;
  }

  public PresentedDecoder.Attempt attempt() {
    return sequential.attempt();
  }

  public PresentedDecoder.Attempt attemptAtFrame(long frame) {
    return new PresentedDecoder.Attempt() {
      private boolean cancelled, shared;
      private JavaAudioResource.StableSession source;
      private OggSeekDecoder controller;
      private PcmDecoder presentation;
      private OggLinks fallback;
      private final Set<SeekableByteChannel> channels =
          Collections.newSetFromMap(new IdentityHashMap<>());

      public PcmDecoder open() throws IOException {
        synchronized (this) {
          check();
          source = snapshot.get();
          shared = source != null;
          if (source == null) source = resource.newStableSession();
        }
        source.initialize();
        synchronized (this) {
          check();
          if (!shared) {
            if (snapshot.compareAndSet(null, source)) shared = true;
            else {
              source.close();
              source = snapshot.get();
              shared = true;
            }
          }
        }
        if (declined) return replay();
        final OggSeekDecoder opened;
        try {
          opened =
              new OggSeekDecoder(
                  () -> {
                    SeekableByteChannel channel =
                        source.openChannel(); // Lazy IO: publish cancellation first.
                    synchronized (this) {
                      if (cancelled) {
                        channel.close();
                        throw new IOException("Ogg seek cancelled");
                      }
                      channels.add(channel);
                    }
                    return channel;
                  },
                  source.length());
        } catch (OggSeekDecoder.UnsupportedSingleLinkException unsupported) {
          declined = true;
          return replay();
        }
        synchronized (this) {
          if (cancelled) {
            opened.close();
            throw new IOException("Late Ogg seek controller");
          }
          controller = opened;
        }
        var result = opened.seek(Math.min(frame, Math.max(0, opened.totalFrames() - 1)));
        synchronized (this) {
          check();
        }
        PcmDecoder normalized =
            new StreamMediaDecoder(
                new StreamMediaDecoder.Input() {
                  private boolean taken;

                  public PcmDecoder nextLink() {
                    if (taken) return null;
                    taken = true;
                    return result.decoder();
                  }

                  public void close() throws IOException {
                    opened.close();
                  }
                },
                targetRate,
                result.sourceFrame(),
                result.sourceFrame());
        synchronized (this) {
          if (!cancelled) {
            presentation = normalized;
            return normalized;
          }
        }
        normalized.close();
        throw new IOException("Late Ogg PCM presentation");
      }

      private PcmDecoder replay() throws IOException {
        SeekableByteChannel channel = source.openChannel();
        OggLinks links = JavaCodecs.oggLinks(Channels.newInputStream(channel));
        synchronized (this) {
          if (cancelled) {
            links.close();
            throw new IOException("Ogg replay cancelled");
          }
          channels.add(channel);
          fallback = links;
        }
        PcmDecoder normalized =
            new StreamMediaDecoder(
                new StreamMediaDecoder.Input() {
                  public PcmDecoder nextLink() throws IOException {
                    return links.nextLink();
                  }

                  public void close() throws IOException {
                    links.close();
                  }
                },
                targetRate,
                0,
                0);
        synchronized (this) {
          if (!cancelled) {
            presentation = normalized;
            return normalized;
          }
        }
        normalized.close();
        throw new IOException("Late Ogg replay presentation");
      }

      private void check() throws IOException {
        if (cancelled) throw new IOException("Ogg seek attempt cancelled");
      }

      public void cancel() {
        List<SeekableByteChannel> active;
        JavaAudioResource.StableSession unfinished;
        OggSeekDecoder decoder;
        PcmDecoder output;
        OggLinks replay;
        synchronized (this) {
          if (cancelled) return;
          cancelled = true;
          active = new ArrayList<>(channels);
          channels.clear();
          unfinished = shared ? null : source;
          decoder = controller;
          output = presentation;
          replay = fallback;
        }
        for (var channel : active)
          try {
            channel.close();
          } catch (IOException ignored) {
          }
        if (unfinished != null)
          try {
            unfinished.close();
          } catch (IOException ignored) {
          }
        if (replay != null)
          try {
            replay.close();
          } catch (IOException ignored) {
          }
        if (decoder != null)
          try {
            decoder.close();
          } catch (IOException ignored) {
          }
        if (output != null)
          try {
            output.close();
          } catch (IOException ignored) {
          }
      }
    };
  }
}
