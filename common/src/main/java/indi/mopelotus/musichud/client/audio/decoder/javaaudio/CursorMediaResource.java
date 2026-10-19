package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Resource owns caches; each cursor owns its own request and cancellation token. No lock covers
 * network IO.
 */
public final class CursorMediaResource implements AutoCloseable {
  public interface Attempt {
    MediaTransport.Response open(Long first, MediaTransport.ExpectedRepresentation expected)
        throws IOException;

    void cancel();
  }

  public interface Opener {
    Attempt attempt();
  }

  /**
   * Parser metadata must carry this key. Only strongly pinned sessions may reuse it across cursors.
   */
  public record Identity(
      UUID resource, long generation, MediaTransport.ExpectedRepresentation representation) {}

  private final Opener opener;
  private final Path directory;
  private final long budget;
  private final boolean forceSingleResponse;
  private final Set<Cursor> cursors = Collections.newSetFromMap(new IdentityHashMap<>());
  private final List<Cache> caches = new ArrayList<>();
  private final AtomicLong generations = new AtomicLong();
  private final UUID identityNamespace = UUID.randomUUID();
  private volatile boolean closed;
  private volatile Cache completed;
  private Cache shared;
  private MediaTransport.ExpectedRepresentation pinned;
  private Identity sharedIdentity;

  public CursorMediaResource(Opener opener, Path directory, long budget) throws IOException {
    this(opener, directory, budget, false);
  }

  public CursorMediaResource(
      Opener opener, Path directory, long budget, boolean forceSingleResponse) throws IOException {
    this.opener = Objects.requireNonNull(opener);
    this.directory = directory;
    this.budget = budget;
    this.forceSingleResponse = forceSingleResponse;
    if (budget < 1) throw new IllegalArgumentException("Invalid cache budget");
    Files.createDirectories(directory);
  }

  public Cursor newCursor() throws IOException {
    return newCursor(null);
  }

  /** A fresh full response, never borrowed prefixes or parser metadata from ranged cursors. */
  public Cursor newSequentialCursor(MediaTransport.ExpectedRepresentation expected)
      throws IOException {
    return newCursor(Objects.requireNonNull(expected));
  }

  private Cursor newCursor(MediaTransport.ExpectedRepresentation requiredWhole) throws IOException {
    Cursor cursor;
    synchronized (this) {
      check();
      cursor = new Cursor(requiredWhole);
      cursors.add(cursor);
      if (pinned != null && requiredWhole == null) {
        cursor.cache = shared;
        cursor.identity = sharedIdentity;
        cursor.length = pinned.totalLength();
        shared.cursorReferences++;
      }
    }
    return cursor;
  }

  /** Convenience only. Cancellable factories should publish newCursor() before initialize(). */
  public Cursor openCursor() throws IOException {
    Cursor cursor = newCursor();
    try {
      return cursor.initialize();
    } catch (Throwable failure) {
      try {
        cursor.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  private synchronized void check() throws ClosedChannelException {
    if (closed) throw new ClosedChannelException();
  }

  /**
   * Pure state inspection: never takes an IO lock, opens a request, or completes a partial cache.
   */
  public boolean hasCompletedSnapshot() {
    Cache captured = completed;
    return !closed && captured != null && !captured.invalidated;
  }

  /** A completed response captured by this resource, not a request to download a snapshot. */
  public synchronized Optional<CompletedSnapshot> completedSnapshot() throws IOException {
    check();
    Cache captured = completed;
    if (captured == null || captured.invalidated || captured.retired) return Optional.empty();
    captured.snapshotReferences++;
    return Optional.of(new CompletedSnapshot(captured));
  }

  /** Metadata only. The caller disposes the returned obsolete cache outside this lock. */
  private synchronized Cache completed(Cache cache, long length) {
    if (closed || cache.invalidated || cache.retired) return null;
    cache.completedLength = length;
    Cache previous = completed;
    completed = cache;
    return previous == cache ? null : retireIfUnused(previous);
  }

  /** Called only under the resource monitor; root close also owns pending disposal failures. */
  private Cache retireIfUnused(Cache cache) {
    if (cache == null
        || cache.retired
        || cache == shared
        || cache == completed
        || cache.cursorReferences != 0
        || cache.snapshotReferences != 0) return null;
    cache.retired = true;
    return cache;
  }

  private void dispose(Cache cache) throws IOException {
    if (cache == null) return;
    cache.close();
    synchronized (this) {
      caches.remove(cache);
    }
  }

  public final class CompletedSnapshot implements AutoCloseable {
    private final Cache cache;
    private final long length;
    private final AtomicBoolean live = new AtomicBoolean(true);

    private CompletedSnapshot(Cache cache) {
      this.cache = cache;
      length = cache.completedLength;
    }

    private void checkSnapshot() throws IOException {
      if (closed || !live.get() || cache.invalidated) throw new ClosedChannelException();
    }

    public long length() throws IOException {
      checkSnapshot();
      return length;
    }

    public Identity identity() throws IOException {
      checkSnapshot();
      return cache.identity;
    }

    public SeekableByteChannel openChannel() throws IOException {
      checkSnapshot();
      return new SeekableByteChannel() {
        private final AtomicBoolean channelOpen = new AtomicBoolean(true);
        private long position;

        private void checkView() throws IOException {
          checkSnapshot();
          if (!channelOpen.get()) throw new ClosedChannelException();
        }

        public int read(ByteBuffer target) throws IOException {
          checkView();
          if (!target.hasRemaining()) return 0;
          if (position >= length) return -1;
          int oldLimit = target.limit();
          target.limit(target.position() + (int) Math.min(target.remaining(), length - position));
          int count;
          try {
            count = cache.file.read(target, position);
          } finally {
            target.limit(oldLimit);
          }
          checkView();
          if (count <= 0) throw new EOFException("Missing completed cache bytes");
          position += count;
          return count;
        }

        public long position() throws IOException {
          checkView();
          return position;
        }

        public SeekableByteChannel position(long value) throws IOException {
          checkView();
          if (value < 0) throw new IllegalArgumentException("Negative position");
          position = value;
          return this;
        }

        public long size() throws IOException {
          checkView();
          return length;
        }

        public boolean isOpen() {
          return channelOpen.get() && live.get() && !closed && !cache.invalidated;
        }

        public void close() {
          channelOpen.set(false);
        }

        public int write(ByteBuffer source) {
          throw new NonWritableChannelException();
        }

        public SeekableByteChannel truncate(long value) {
          throw new NonWritableChannelException();
        }
      };
    }

    public void close() throws IOException {
      if (!live.compareAndSet(true, false)) return;
      Cache discard;
      synchronized (CursorMediaResource.this) {
        cache.snapshotReferences--;
        discard = retireIfUnused(cache);
      }
      dispose(discard);
    }
  }

  private static final class Cache implements AutoCloseable {
    final Path path;
    final FileChannel file;
    final TreeMap<Long, Long> spans = new TreeMap<>();
    // Published only after all required intervals have been committed. Getters never inspect disk.
    volatile long completedLength = -1;
    volatile boolean invalidated;
    boolean retired;
    // Guarded by the resource monitor. Every cursor and snapshot owns exactly one reference.
    int cursorReferences;
    int snapshotReferences;
    Identity identity;

    Cache(Path directory) throws IOException {
      path = Files.createTempFile(directory, "cursor-owned-", ".cache");
      try {
        file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
      } catch (Throwable failure) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    long end(long position) {
      var e = spans.floorEntry(position);
      return e != null && e.getValue() > position ? e.getValue() : position;
    }

    void add(long start, long end) {
      var before = spans.floorEntry(start);
      if (before != null && before.getValue() >= start) {
        start = before.getKey();
        end = Math.max(end, before.getValue());
        spans.remove(before.getKey());
      }
      var next = spans.ceilingEntry(start);
      while (next != null && next.getKey() <= end) {
        end = Math.max(end, next.getValue());
        spans.remove(next.getKey());
        next = spans.ceilingEntry(start);
      }
      spans.put(start, end);
    }

    public void close() throws IOException {
      try {
        file.close();
      } finally {
        Files.deleteIfExists(path);
      }
    }
  }

  public final class Cursor extends InputStream {
    private final MediaTransport.ExpectedRepresentation requiredWhole;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private volatile MediaTransport.Response response;
    private Cache cache;
    private Identity identity;
    private long position, streamPosition, length = -1;
    private int icyMetaInt;
    private boolean streamingOnly;
    private final byte[] discardBuffer = new byte[8192];

    private Cursor(MediaTransport.ExpectedRepresentation requiredWhole) {
      this.requiredWhole = requiredWhole;
    }

    private void checkCursor() throws IOException {
      if (!live.get()) throw new ClosedChannelException();
      check();
    }

    private void current(long generation) throws IOException {
      checkCursor();
      if (generation != epoch.get()) throw new IOException("Stale cursor operation");
    }

    private void start(Long first) throws IOException {
      checkCursor();
      long generation = epoch.get();
      Attempt a = opener.attempt();
      if (!active.compareAndSet(null, a))
        throw new IOException("Concurrent cursor requests are prohibited");
      try {
        current(generation);
        MediaTransport.ExpectedRepresentation expected =
            identity == null ? null : identity.representation();
        MediaTransport.Response opened = a.open(first, expected);
        try {
          current(generation);
          if (opened.status() != (first == null ? 200 : 206))
            throw new IOException("Unexpected media status; reopen a fresh parser session");
          if (requiredWhole != null)
            MediaTransport.validateWholeRepresentation(requiredWhole, opened.representation());
          if (cache == null) {
            var representation = opened.representation();
            if (opened.icyMetaInt() == 0 && representation.totalLength() > budget)
              throw new IOException("Media exceeds cache budget");
            // Only strong ETags share cache or parser metadata between responses/cursors.
            boolean strong =
                !forceSingleResponse
                    && requiredWhole == null
                    && representation.canRange()
                    && representation.expected().validator() instanceof MediaTransport.StrongETag;
            // File creation is outside the metadata monitor used by snapshot borrowing.
            Cache created = new Cache(directory);
            try {
              synchronized (CursorMediaResource.this) {
                current(generation);
                if (strong && pinned == null) {
                  pinned = representation.expected();
                  shared = created;
                  caches.add(created);
                  created = null;
                  sharedIdentity =
                      new Identity(identityNamespace, generations.incrementAndGet(), pinned);
                }
                if (strong && pinned.equals(representation.expected())) {
                  cache = shared;
                  identity = sharedIdentity;
                } else {
                  cache = created;
                  caches.add(created);
                  created = null;
                  identity = new Identity(identityNamespace, generations.incrementAndGet(), null);
                }
                cache.identity = identity;
                cache.cursorReferences++;
              }
            } finally {
              if (created != null) created.close();
            }
          }
          response = opened;
          icyMetaInt = opened.icyMetaInt();
          streamingOnly = icyMetaInt > 0;
          streamPosition = first == null ? 0 : first;
          length = streamingOnly ? -1 : opened.representation().totalLength();
          if (length > budget) throw new IOException("Media exceeds cache budget");
          current(generation);
          if (!streamingOnly && length == 0) dispose(completed(cache, 0));
        } catch (Throwable failure) {
          opened.close();
          throw failure;
        }
      } catch (Throwable failure) {
        if (failure instanceof MediaTransport.ResourceChangedException && cache != null)
          cache.invalidated = true;
        active.compareAndSet(a, null);
        a.cancel();
        throw failure;
      }
    }

    public Cursor initialize() throws IOException {
      checkCursor();
      if (cache == null) start(null);
      return this;
    }

    public Identity identity() throws IOException {
      initialize();
      return identity;
    }

    public void requireIdentity(Identity expected) throws IOException {
      if (!identity().equals(expected))
        throw new IOException("Parser metadata belongs to a different response session");
    }

    public URI finalUri() throws IOException {
      initialize();
      if (identity.representation() != null) return identity.representation().finalUri();
      return response.finalUri();
    }

    public int icyMetaInt() throws IOException {
      initialize();
      return icyMetaInt;
    }

    public long knownLength() throws IOException {
      initialize();
      return length;
    }

    /** Explicit random-access size request. Streaming decoders must not call this. */
    public long completeLength() throws IOException {
      initialize();
      if (streamingOnly) throw new IOException("Live ICY audio has no finite length");
      if (length >= 0) return length;
      long saved = position;
      position = streamPosition;
      try {
        byte[] scratch = new byte[8192];
        while (read(scratch) >= 0) {}
        return length;
      } finally {
        position = saved;
      }
    }

    /** Explicitly completes this response snapshot, including a known Content-Length. */
    public long materialize() throws IOException {
      initialize();
      if (streamingOnly) throw new IOException("Live ICY audio cannot be materialized");
      long saved = position;
      position = streamPosition;
      try {
        byte[] scratch = new byte[8192];
        while (read(scratch) >= 0) {}
        return length;
      } finally {
        position = saved;
      }
    }

    /** One adapter per cursor; closing this channel closes the same cursor lease. */
    public SeekableByteChannel asChannel() {
      return new SeekableByteChannel() {
        final byte[] scratch = new byte[8192];

        public int read(ByteBuffer target) throws IOException {
          checkCursor();
          if (!target.hasRemaining()) return 0;
          if (target.hasArray()) {
            int n =
                Cursor.this.read(
                    target.array(),
                    target.arrayOffset() + target.position(),
                    Math.min(8192, target.remaining()));
            if (n > 0) target.position(target.position() + n);
            return n;
          }
          int n = Cursor.this.read(scratch, 0, Math.min(scratch.length, target.remaining()));
          if (n > 0) target.put(scratch, 0, n);
          return n;
        }

        public long position() throws IOException {
          return Cursor.this.position();
        }

        public SeekableByteChannel position(long value) throws IOException {
          Cursor.this.position(value);
          return this;
        }

        public long size() throws IOException {
          return completeLength();
        }

        public boolean isOpen() {
          synchronized (CursorMediaResource.this) {
            return live.get() && !closed;
          }
        }

        public void close() throws IOException {
          Cursor.this.close();
        }

        public int write(ByteBuffer bytes) {
          throw new NonWritableChannelException();
        }

        public SeekableByteChannel truncate(long size) {
          throw new NonWritableChannelException();
        }
      };
    }

    public long position() throws IOException {
      checkCursor();
      return position;
    }

    /**
     * Backward reads within this response are cached; unvalidated uncached forward seeks stream and
     * discard.
     */
    public void position(long value) throws IOException {
      checkCursor();
      if (value < 0 || value > budget) throw new IllegalArgumentException("Invalid position");
      if (streamingOnly && value != position)
        throw new IOException("Live ICY audio is not seekable");
      position = value;
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      int n = read(b, 0, 1);
      return n < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] target, int offset, int count) throws IOException {
      Objects.checkFromIndexSize(offset, count, target.length);
      checkCursor();
      if (count == 0) return 0;
      initialize();
      long generation = epoch.get();
      if (length >= 0 && position >= length) return -1;
      synchronized (cache) {
        current(generation);
        long end = cache.end(position);
        if (end > position) {
          int wanted = (int) Math.min(count, end - position);
          int n = cache.file.read(ByteBuffer.wrap(target, offset, wanted), position);
          if (n <= 0) throw new EOFException("Missing cached data");
          current(generation);
          position += n;
          return n;
        }
      }
      if (response == null || streamPosition != position && identity.representation() != null) {
        abortRequest();
        current(generation);
        start(position);
      }
      if (streamPosition > position)
        throw new IOException(
            "Missing bytes from unvalidated response; create a fresh parser session");
      while (streamPosition < position) {
        int n =
            receive(
                discardBuffer,
                0,
                (int) Math.min(discardBuffer.length, position - streamPosition),
                generation);
        if (n < 0) return -1;
      }
      int n = receive(target, offset, Math.min(count, 8192), generation);
      if (n > 0) position += n;
      return n;
    }

    private int receive(byte[] bytes, int offset, int count, long generation) throws IOException {
      current(generation);
      MediaTransport.Response reading = response;
      if (reading == null) throw new IOException("Request was cancelled");
      int n = reading.body().read(bytes, offset, count);
      current(generation);
      if (n == 0) throw new IOException("Media stream made no progress");
      if (n < 0) {
        if (length >= 0 && streamPosition < length)
          throw new EOFException("Truncated media response");
        length = streamPosition;
        Cache discard = null;
        if (!streamingOnly) {
          synchronized (cache) {
            if (cache.end(0) >= length) discard = completed(cache, length);
          }
        }
        dispose(discard);
        abortRequest();
        return -1;
      }
      if (streamingOnly) {
        streamPosition = Math.addExact(streamPosition, n);
        return n;
      }
      if (streamPosition > budget - n) throw new IOException("Media exceeds cache budget");
      Cache discard = null;
      synchronized (cache) {
        current(generation);
        ByteBuffer data = ByteBuffer.wrap(bytes, offset, n);
        long p = streamPosition;
        while (data.hasRemaining()) {
          int written = cache.file.write(data, p);
          if (written <= 0) throw new IOException("Cache write made no progress");
          p += written;
        }
        current(generation);
        cache.add(streamPosition, streamPosition + n);
        streamPosition += n;
        if (length >= 0 && cache.end(0) >= length) discard = completed(cache, length);
      }
      dispose(discard);
      return n;
    }

    private void abortRequest() {
      Attempt a = active.getAndSet(null);
      if (a != null) a.cancel();
      response = null;
    }

    /**
     * Never acquires a read/cache lock and immediately aborts this cursor's opening or blocked
     * request.
     */
    public void close() throws IOException {
      if (live.compareAndSet(true, false)) {
        epoch.incrementAndGet();
        abortRequest();
        Cache discard;
        synchronized (CursorMediaResource.this) {
          cursors.remove(this);
          if (cache != null) cache.cursorReferences--;
          discard = retireIfUnused(cache);
        }
        dispose(discard);
      }
    }
  }

  public void close() throws IOException {
    List<Cursor> active;
    List<Cache> owned;
    synchronized (this) {
      if (closed) return;
      closed = true;
      completed = null;
      active = new ArrayList<>(cursors);
      owned = new ArrayList<>(caches);
      for (Cache cache : owned) cache.retired = true;
    }
    IOException failure = null;
    for (Cursor cursor : active)
      try {
        cursor.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    for (Cache cache : owned)
      try {
        cache.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    if (failure != null) throw failure;
  }

  /**
   * Uses original URI on every request, retaining MediaTransport redirect and credential policy.
   */
  public static Opener transport(
      MediaTransport transport, URI uri, Map<String, String> headers, boolean publicOnly) {
    Map<String, String> copied = Map.copyOf(headers);
    return () ->
        new Attempt() {
          final MediaTransport.Cancellation cancellation = new MediaTransport.Cancellation();

          public MediaTransport.Response open(
              Long first, MediaTransport.ExpectedRepresentation expected) throws IOException {
            return transport.open(
                uri,
                copied,
                publicOnly,
                first == null ? null : new MediaTransport.ByteRange(first, null),
                cancellation,
                first == null ? null : expected);
          }

          public void cancel() {
            cancellation.cancel();
          }
        };
  }
}
