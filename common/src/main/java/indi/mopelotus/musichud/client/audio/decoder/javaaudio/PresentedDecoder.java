package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** One-reader edited PCM stream; concurrent seek/open/close never publishes stale frames. */
public final class PresentedDecoder implements PcmDecoder, PcmChannelConversion {
  public interface Attempt {
    PcmDecoder open() throws IOException;

    /** Idempotent; cancels opening and closes any decoder returned by this attempt. */
    void cancel();
  }

  public interface Factory {
    Attempt attempt();

    /**
     * Returns raw-media PCM at or before this absolute frame. Default is an exact from-start
     * fallback.
     */
    default Attempt attemptAtFrame(long mediaFrame) {
      return attempt();
    }
  }

  private final Factory factory;
  private final PresentationPlan plan;
  private final PcmFormat format;
  private final Object state = new Object(), reader = new Object();
  private final Set<Attempt> pending = Collections.newSetFromMap(new IdentityHashMap<>());
  private Attempt owned;
  private PcmDecoder decoder;
  private long epoch, position;
  private volatile boolean closed;
  private boolean ready = true;
  private volatile boolean downmixed;

  public PresentedDecoder(Factory factory, PresentationPlan plan) throws IOException {
    this.factory = Objects.requireNonNull(factory);
    this.plan = Objects.requireNonNull(plan);
    Attempt attempt = factory.attempt();
    try {
      decoder = attempt.open();
      format = decoder.format();
      observeChannels(decoder);
      owned = attempt;
    } catch (Throwable error) {
      attempt.cancel();
      throw error;
    }
  }

  private void observeChannels(PcmDecoder input) {
    if (input instanceof PcmChannelConversion conversion)
      downmixed |= conversion.hasDownmixedChannels();
  }

  public boolean hasDownmixedChannels() {
    return downmixed;
  }

  public PcmFormat format() {
    return format;
  }

  public long framePosition() {
    synchronized (state) {
      return position;
    }
  }

  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    if (maxFrames < 0
        || offset < 0
        || offset > target.length - Math.multiplyExact(maxFrames, format.frameBytes()))
      throw new IndexOutOfBoundsException();
    synchronized (reader) {
      final long version, initial;
      synchronized (state) {
        check();
        if (!ready) throw new IOException("Seek in progress");
        version = epoch;
        initial = position;
      }
      if (maxFrames == 0) return 0;
      long remaining = initial;
      PresentationPlan.Segment selected = null;
      for (var segment : plan.segments()) {
        if (remaining < segment.frames()) {
          selected = segment;
          break;
        }
        remaining -= segment.frames();
      }
      if (selected == null) return endOfStream(version);
      int count = (int) Math.min(maxFrames, selected.frames() - remaining);
      if (selected.silence())
        Arrays.fill(target, offset, offset + count * format.frameBytes(), (byte) 0);
      else {
        long media = Math.addExact(selected.mediaFrame(), remaining);
        PcmDecoder current;
        synchronized (state) {
          checkEpoch(version);
          current = decoder;
        }
        if (current.framePosition() > media) current = reopen(version, media);
        skip(current, media);
        count = current.readFrames(target, offset, count);
        observeChannels(current);
        if (count < 0) {
          if (plan.totalFrames() == Long.MAX_VALUE) return endOfStream(version);
          throw new EOFException("Media ended before declared presentation edit");
        }
        if (count == 0) throw new IOException("Decoder made no progress");
      }
      synchronized (state) {
        checkEpoch(version);
        position = Math.addExact(position, count);
      }
      return count;
    }
  }

  private PcmDecoder reopen(long version, long requestedMediaFrame) throws IOException {
    Attempt attempt = factory.attemptAtFrame(requestedMediaFrame);
    try {
      synchronized (state) {
        checkEpoch(version);
        pending.add(attempt);
      }
    } catch (IOException error) {
      attempt.cancel();
      throw error;
    }
    PcmDecoder next;
    try {
      next = attempt.open();
    } catch (Throwable error) {
      synchronized (state) {
        pending.remove(attempt);
      }
      attempt.cancel();
      throw error;
    }
    Attempt previous;
    synchronized (state) {
      pending.remove(attempt);
      if (closed || epoch != version) {
        attempt.cancel();
        throw new IOException("Stale decoder open");
      }
      if (!format.equals(next.format())) {
        attempt.cancel();
        throw new IOException("Reopened PCM format changed");
      }
      if (next.framePosition() > requestedMediaFrame) {
        attempt.cancel();
        throw new IOException("Seek decoder overshot requested media frame");
      }
      previous = owned;
      owned = attempt;
      decoder = next;
      observeChannels(next);
    }
    if (previous != null) previous.cancel();
    return next;
  }

  private void skip(PcmDecoder input, long target) throws IOException {
    byte[] scratch = new byte[4096 * format.frameBytes()];
    while (input.framePosition() < target) {
      if (closed) throw new IOException("Decoder closed");
      int n = input.readFrames(scratch, 0, (int) Math.min(4096, target - input.framePosition()));
      if (n < 0) throw new EOFException("Seek beyond media PCM");
      if (n == 0) throw new IOException("Decoder made no progress during seek");
    }
  }

  /**
   * Maps presentation frames to media time; the factory selects a qualified index or exact
   * fallback.
   */
  public boolean seekFrame(long target) throws IOException {
    if (target < 0 || target > plan.totalFrames()) return false;
    long version;
    Attempt previous;
    synchronized (state) {
      check();
      version = ++epoch;
      ready = false;
      previous = owned;
      owned = null;
      decoder = null;
    }
    if (previous != null) previous.cancel();
    // Resolve edits before reopening so an indexed source sees the true raw-media target.
    long remaining = target, wanted = -1;
    for (var segment : plan.segments()) {
      if (remaining >= segment.frames()) {
        remaining -= segment.frames();
        continue;
      }
      if (segment.silence()) {
        remaining = 0;
        continue;
      }
      wanted = Math.addExact(segment.mediaFrame(), remaining);
      break;
    }
    // Opening is outside reader lock: a slow old request cannot prevent newer seek/close.
    if (wanted >= 0) {
      PcmDecoder next = reopen(version, wanted);
      skip(next, wanted);
    }
    synchronized (state) {
      checkEpoch(version);
      position = target;
      ready = true;
    }
    return true;
  }

  private int endOfStream(long version) throws IOException {
    synchronized (state) {
      checkEpoch(version);
      return -1;
    }
  }

  private void check() throws IOException {
    if (closed) throw new IOException("Decoder closed");
  }

  private void checkEpoch(long expected) throws IOException {
    check();
    if (epoch != expected) throw new IOException("Stale playback generation");
  }

  public void close() {
    List<Attempt> cancelled;
    synchronized (state) {
      if (closed) return;
      closed = true;
      epoch++;
      cancelled = new ArrayList<>(pending);
      pending.clear();
      if (owned != null) cancelled.add(owned);
      owned = null;
      decoder = null;
    }
    for (var attempt : cancelled) attempt.cancel();
  }
}
