package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.container.input.IcyMetadataInputStream;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.CursorMediaResource;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Playback owns this resource; each decoder attempt owns one independently cancellable lease. */
final class JavaAudioResource implements AutoCloseable {
  private final Path local;
  private final CursorMediaResource remote;
  private final MediaTransport.ExpectedRepresentation requiredWhole;
  private final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<>());
  private final AtomicBoolean open = new AtomicBoolean(true);
  private volatile boolean icy, rangeStable;

  JavaAudioResource(
      String identifier, Map<String, String> headers, boolean publicOnly, boolean singleResponse)
      throws IOException {
    this(identifier, headers, publicOnly, singleResponse, null);
  }

  JavaAudioResource(
      String identifier, Map<String, String> headers, boolean publicOnly, boolean singleResponse,
      MediaTransport.ExpectedRepresentation requiredWhole) throws IOException {
    this.requiredWhole = requiredWhole;
    if (identifier.regionMatches(true, 0, "icy://", 0, 6))
      identifier = "http://" + identifier.substring(6);
    boolean http =
        identifier.regionMatches(true, 0, "http://", 0, 7)
            || identifier.regionMatches(true, 0, "https://", 0, 8);
    if (!http) {
      if (publicOnly) throw new IOException("Public audio must use HTTP");
      local =
          identifier.regionMatches(true, 0, "file:", 0, 5)
              ? Path.of(URI.create(identifier))
              : Path.of(identifier);
      remote = null;
    } else {
      local = null;
      URI uri;
      try {
        uri = URI.create(identifier);
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid media URL");
      }
      remote =
          new CursorMediaResource(
              CursorMediaResource.transport(
                  PcmAudioInput.sharedTransport(), uri, headers, publicOnly),
              Path.of(System.getProperty("java.io.tmpdir"), "musichud-tuneweave", "media"),
              4L * 1024 * 1024 * 1024,
              singleResponse);
    }
  }

  boolean isLocal() {
    return local != null;
  }

  /** No IO: this does not materialize a stream or build an index. */
  boolean hasCompletedSnapshot() {
    return open.get() && remote != null && remote.hasCompletedSnapshot();
  }

  Optional<CompletedSnapshot> completedSnapshot() throws IOException {
    if (!open.get()) throw new ClosedChannelException();
    if (remote == null) return Optional.empty();
    var captured = remote.completedSnapshot();
    if (!open.get()) {
      if (captured.isPresent()) captured.orElseThrow().close();
      throw new ClosedChannelException();
    }
    return captured.map(CompletedSnapshot::new);
  }

  final class CompletedSnapshot implements AutoCloseable {
    private final CursorMediaResource.CompletedSnapshot captured;

    private CompletedSnapshot(CursorMediaResource.CompletedSnapshot captured) {
      this.captured = captured;
    }

    long length() throws IOException {
      if (!open.get()) throw new ClosedChannelException();
      return captured.length();
    }

    SeekableByteChannel openChannel() throws IOException {
      if (!open.get()) throw new ClosedChannelException();
      return captured.openChannel();
    }

    public void close() throws IOException {
      captured.close();
    }
  }

  boolean rangeStable() {
    return remote == null || rangeStable;
  }

  boolean isIcy() {
    return icy;
  }

  Lease lease() throws IOException {
    return lease(requiredWhole);
  }

  private Lease lease(MediaTransport.ExpectedRepresentation expected) throws IOException {
    synchronized (leases) {
      if (!open.get()) throw new ClosedChannelException();
      Lease lease = new Lease(remote == null ? null
          : expected == null ? remote.newCursor() : remote.newSequentialCursor(expected));
      leases.add(lease);
      return lease;
    }
  }

  final class Lease implements AutoCloseable {
    private final CursorMediaResource.Cursor cursor;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private volatile SeekableByteChannel physical;
    private InputStream stream;
    private SeekableByteChannel channel;

    Lease(CursorMediaResource.Cursor cursor) {
      this.cursor = cursor;
    }

    private void initialize() throws IOException {
      if (!live.get() || !open.get()) throw new ClosedChannelException();
      if (physical != null) return;
      SeekableByteChannel opened;
      if (cursor != null) {
        cursor.initialize();
        icy |= cursor.icyMetaInt() > 0;
        rangeStable |= cursor.identity().representation() != null;
        opened = cursor.asChannel();
      } else opened = Files.newByteChannel(local);
      physical = opened;
      if (!live.get() || !open.get()) {
        opened.close();
        throw new ClosedChannelException();
      }
    }

    InputStream stream() throws IOException {
      initialize();
      if (stream == null) {
        InputStream raw = cursor == null ? Channels.newInputStream(physical) : cursor;
        stream =
            new FilterInputStream(raw) {
              public void close() throws IOException {
                Lease.this.close();
              }
            };
        if (icyMetaInt() > 0) stream = new IcyMetadataInputStream(stream, icyMetaInt(), null);
      }
      return stream;
    }

    boolean stronglyPinned() throws IOException {
      initialize();
      return cursor == null || cursor.identity().representation() != null;
    }

    long materialize() throws IOException {
      initialize();
      return cursor == null ? physical.size() : cursor.materialize();
    }

    URI finalUri() throws IOException {
      initialize();
      return cursor == null ? local.toAbsolutePath().toUri() : cursor.finalUri();
    }

    int icyMetaInt() throws IOException {
      initialize();
      return cursor == null ? 0 : cursor.icyMetaInt();
    }

    private void seekable() throws IOException {
      initialize();
      if (icyMetaInt() > 0) throw new IOException("ICY radio has no byte-addressable audio stream");
    }

    SeekableByteChannel channel() throws IOException {
      if (!live.get() || !open.get()) throw new ClosedChannelException();
      if (channel == null)
        channel =
            new SeekableByteChannel() {
              public int read(ByteBuffer target) throws IOException {
                seekable();
                return physical.read(target);
              }

              public long position() throws IOException {
                seekable();
                return physical.position();
              }

              public SeekableByteChannel position(long value) throws IOException {
                seekable();
                physical.position(value);
                return this;
              }

              public long size() throws IOException {
                seekable();
                return physical.size();
              }

              public int write(ByteBuffer bytes) {
                throw new NonWritableChannelException();
              }

              public SeekableByteChannel truncate(long size) {
                throw new NonWritableChannelException();
              }

              public boolean isOpen() {
                return live.get() && open.get() && (physical == null || physical.isOpen());
              }

              public void close() throws IOException {
                Lease.this.close();
              }
            };
      return channel;
    }

    public void close() throws IOException {
      if (!live.compareAndSet(true, false)) return;
      synchronized (leases) {
        leases.remove(this);
      }
      if (cursor != null) cursor.close();
      else if (physical != null) physical.close();
    }
  }

  /** Prepare only when random access is requested; ordinary playback never downloads ahead. */
  StableSession newStableSession() throws IOException {
    return new StableSession(lease());
  }

  Lease sequentialLease(MediaTransport.ExpectedRepresentation expected) throws IOException {
    return lease(Objects.requireNonNull(expected));
  }

  final class StableSession implements AutoCloseable {
    private final Lease anchor;
    private final Object io = new Object();
    private final AtomicBoolean live = new AtomicBoolean(true);
    private SeekableByteChannel snapshot;
    private boolean ranged;
    private long length = -1;

    StableSession(Lease anchor) {
      this.anchor = anchor;
    }

    void initialize() throws IOException {
      if (!live.get()) throw new ClosedChannelException();
      if (length >= 0) return;
      SeekableByteChannel channel = anchor.channel();
      ranged = anchor.stronglyPinned();
      long size = ranged ? channel.size() : anchor.materialize();
      if (!live.get()) throw new ClosedChannelException();
      snapshot = channel;
      length = size;
    }

    long length() throws IOException {
      initialize();
      return length;
    }

    SeekableByteChannel openChannel() throws IOException {
      initialize();
      if (ranged) return lease().channel();
      // A weak entity is one captured response. Independent cursors borrow its completed bytes;
      // closing an indexed decoder must not destroy the playback-owned snapshot.
      return new SeekableByteChannel() {
        private boolean closed;
        private long position;

        private void check() throws IOException {
          if (closed || !live.get() || !open.get()) throw new ClosedChannelException();
        }

        public int read(ByteBuffer target) throws IOException {
          synchronized (io) {
            check();
            snapshot.position(position);
            int read = snapshot.read(target);
            if (read > 0) position += read;
            return read;
          }
        }

        public long position() throws IOException {
          check();
          return position;
        }

        public SeekableByteChannel position(long value) throws IOException {
          check();
          if (value < 0) throw new IllegalArgumentException("Negative position");
          position = value;
          return this;
        }

        public long size() throws IOException {
          check();
          return length;
        }

        public boolean isOpen() {
          return !closed && live.get() && open.get();
        }

        public int write(ByteBuffer bytes) {
          throw new NonWritableChannelException();
        }

        public SeekableByteChannel truncate(long value) {
          throw new NonWritableChannelException();
        }

        public void close() {
          closed = true;
        }
      };
    }

    public void close() throws IOException {
      if (live.compareAndSet(true, false)) anchor.close();
    }
  }

  public void close() throws IOException {
    if (!open.compareAndSet(true, false)) return;
    List<Lease> owned;
    synchronized (leases) {
      owned = new ArrayList<>(leases);
    }
    IOException failure = null;
    for (var lease : owned)
      try {
        lease.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    if (remote != null)
      try {
        remote.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    if (failure != null) throw failure;
  }
}
