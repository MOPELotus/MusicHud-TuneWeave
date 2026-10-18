package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.PresentedDecoder;
import java.io.*;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exact indexes are built only from local data or an already-complete response snapshot. */
final class Mp3PlaybackFactory implements PresentedDecoder.Factory {
  private final JavaAudioResource resource;
  private final PresentedDecoder.Factory sequential;
  private final AtomicReference<Source> source = new AtomicReference<>();
  private final AtomicReference<Mp3SeekIndex> index = new AtomicReference<>();
  private volatile boolean declined;

  Mp3PlaybackFactory(JavaAudioResource resource, PresentedDecoder.Factory sequential) {
    this.resource = resource;
    this.sequential = sequential;
  }

  public boolean supportsFastSeek() {
    return !declined
        && (resource.isLocal() || source.get() != null || resource.hasCompletedSnapshot());
  }

  public void prepareSeek() throws IOException {
    if (source.get() != null) return;
    Source captured;
    if (resource.isLocal()) captured = new Source(resource.newStableSession(), null);
    else {
      var completed = resource.completedSnapshot();
      if (completed.isEmpty()) return;
      captured = new Source(null, completed.get());
    }
    if (!source.compareAndSet(null, captured)) captured.close();
  }

  private record Source(
      JavaAudioResource.StableSession local, JavaAudioResource.CompletedSnapshot completed)
      implements AutoCloseable {
    SeekableByteChannel open() throws IOException {
      return local == null ? completed.openChannel() : local.openChannel();
    }

    public void close() throws IOException {
      if (local == null) completed.close();
      else local.close();
    }
  }

  public PresentedDecoder.Attempt attempt() {
    return sequential.attempt();
  }

  public PresentedDecoder.Attempt attemptAtFrame(long requested) {
    Source captured = source.get();
    if (captured == null) return sequential.attemptAtFrame(requested);
    return new PresentedDecoder.Attempt() {
      private volatile boolean cancelled;
      private SeekableByteChannel channel;
      private PcmDecoder decoder;

      private synchronized void check() throws IOException {
        if (cancelled) throw new InterruptedIOException("MP3 seek cancelled");
      }

      private InputStream open(long offset) throws IOException {
        check();
        SeekableByteChannel opened = captured.open();
        synchronized (this) {
          if (cancelled) {
            opened.close();
            throw new InterruptedIOException("Late MP3 source");
          }
          channel = opened;
        }
        opened.position(offset);
        return Channels.newInputStream(opened);
      }

      public PcmDecoder open() throws IOException {
        Mp3SeekIndex qualified = index.get();
        if (!declined && qualified == null) {
          try {
            Mp3SeekIndex built = Mp3SeekIndex.build(open(0), () -> cancelled);
            synchronized (this) {
              check();
              index.compareAndSet(null, built);
              qualified = index.get();
            }
          } catch (IOException unavailable) {
            check();
            // The captured bytes never change. Unsupported tags may decline indexing while
            // ordinary decoding still succeeds; actual corrupt cache/input still fails on replay.
            declined = true;
          }
        }
        PcmDecoder opened;
        if (declined) opened = JavaCodecs.mp3(open(0));
        else {
          var plan =
              qualified.plan(Math.min(requested, Math.max(0, qualified.audibleFrames() - 1)));
          opened = JavaCodecs.mp3Indexed(open(plan.byteOffset()), qualified, plan);
        }
        synchronized (this) {
          if (!cancelled) {
            decoder = opened;
            return opened;
          }
        }
        opened.close();
        throw new InterruptedIOException("Late MP3 decoder");
      }

      public void cancel() {
        SeekableByteChannel active;
        PcmDecoder output;
        synchronized (this) {
          if (cancelled) return;
          cancelled = true;
          active = channel;
          output = decoder;
        }
        if (active != null)
          try {
            active.close();
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
