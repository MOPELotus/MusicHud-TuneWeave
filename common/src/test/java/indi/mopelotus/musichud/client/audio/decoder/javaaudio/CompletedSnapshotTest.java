package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CompletedSnapshotTest {
  @TempDir Path directory;
  private final byte[] bytes = {2, 4, 6, 8, 10, 12, 14, 16};
  private final AtomicInteger requests = new AtomicInteger();

  private CursorMediaResource resource(boolean strong, boolean known) throws IOException {
    URI uri = URI.create("https://example.test/song");
    return new CursorMediaResource(
        () ->
            new CursorMediaResource.Attempt() {
              public MediaTransport.Response open(
                  Long first, MediaTransport.ExpectedRepresentation expected) {
                requests.incrementAndGet();
                int start = first == null ? 0 : first.intValue();
                return new MediaTransport.Response(
                    uri,
                    first == null ? 200 : 206,
                    known ? bytes.length - start : -1,
                    new ByteArrayInputStream(bytes, start, bytes.length - start),
                    new MediaTransport.Representation(
                        uri,
                        known ? bytes.length : -1,
                        strong ? "\"stable\"" : "W/\"weak\"",
                        null,
                        null,
                        true));
              }

              public void cancel() {}
            },
        directory,
        1024);
  }

  @Test
  void inspectingAnUnopenedOrPartialResourceDoesNotOpenOrMaterializeIt() throws Exception {
    try (var resource = resource(false, true)) {
      assertFalse(resource.hasCompletedSnapshot());
      assertTrue(resource.completedSnapshot().isEmpty());
      assertEquals(0, requests.get());
      try (var cursor = resource.openCursor()) {
        assertEquals(bytes[0], cursor.read());
        assertFalse(resource.hasCompletedSnapshot());
        assertTrue(resource.completedSnapshot().isEmpty());
        assertEquals(1, requests.get());
        assertEquals(1, cursor.position());
      }
      assertTrue(resource.completedSnapshot().isEmpty());
      try (var files = Files.list(directory)) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void completedWeakResponseSurvivesCursorCloseWithIndependentReadOnlyViews() throws Exception {
    Path sentinel = directory.resolve("cursor-owned-user-file.cache");
    Files.writeString(sentinel, "keep");
    try (var resource = resource(false, true)) {
      CursorMediaResource.Identity identity;
      try (var cursor = resource.openCursor()) {
        identity = cursor.identity();
        assertArrayEquals(bytes, cursor.readNBytes(bytes.length));
      }
      assertTrue(resource.hasCompletedSnapshot());
      try (var snapshot = resource.completedSnapshot().orElseThrow()) {
        assertEquals(bytes.length, snapshot.length());
        assertEquals(identity, snapshot.identity());
        var first = snapshot.openChannel();
        var second = snapshot.openChannel();
        first.position(6);
        second.position(1);
        var tail = ByteBuffer.allocate(2);
        assertEquals(2, first.read(tail));
        assertArrayEquals(new byte[] {14, 16}, tail.array());
        first.close();
        var prefix = ByteBuffer.allocateDirect(3);
        assertEquals(3, second.read(prefix));
        prefix.flip();
        assertEquals(4, prefix.get());
        assertEquals(6, prefix.get());
        assertEquals(8, prefix.get());
        assertThrows(NonWritableChannelException.class, () -> second.write(ByteBuffer.allocate(1)));
        assertThrows(NonWritableChannelException.class, () -> second.truncate(1));
        snapshot.close();
        assertFalse(second.isOpen());
        assertThrows(ClosedChannelException.class, () -> second.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, snapshot::openChannel);
        second.close();
      }
      // Releasing one borrowed snapshot does not invalidate the playback-owned completed bytes.
      try (var next = resource.completedSnapshot().orElseThrow();
          var view = next.openChannel()) {
        assertEquals(bytes.length, view.size());
      }
      assertEquals(1, requests.get());
      try (var files = Files.list(directory)) {
        assertEquals(2, files.count());
      }
    }
    assertEquals("keep", Files.readString(sentinel));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(sentinel), files.toList());
    }
  }

  @Test
  void unknownLengthBecomesBorrowableOnlyAfterItsSameResponseReachesEof() throws Exception {
    try (var resource = resource(false, false);
        var cursor = resource.openCursor()) {
      assertArrayEquals(bytes, cursor.readNBytes(bytes.length));
      assertFalse(resource.hasCompletedSnapshot());
      assertEquals(-1, cursor.knownLength());
      assertEquals(-1, cursor.read());
      try (var snapshot = resource.completedSnapshot().orElseThrow()) {
        assertEquals(bytes.length, snapshot.length());
      }
      assertEquals(1, requests.get());
    }
  }

  @Test
  void strongSparseRangesAreNotCompleteUntilEveryByteIsCommitted() throws Exception {
    try (var resource = resource(true, true);
        var cursor = resource.openCursor()) {
      assertArrayEquals(Arrays.copyOf(bytes, 2), cursor.readNBytes(2));
      cursor.position(6);
      assertArrayEquals(Arrays.copyOfRange(bytes, 6, 8), cursor.readNBytes(2));
      assertFalse(resource.hasCompletedSnapshot());
      cursor.position(2);
      assertArrayEquals(Arrays.copyOfRange(bytes, 2, 6), cursor.readNBytes(4));
      assertTrue(resource.hasCompletedSnapshot());
      int before = requests.get();
      try (var snapshot = resource.completedSnapshot().orElseThrow();
          var view = snapshot.openChannel()) {
        var output = ByteBuffer.allocate(bytes.length);
        assertEquals(bytes.length, view.read(output));
        assertArrayEquals(bytes, output.array());
      }
      assertEquals(before, requests.get());
    }
  }

  @Test
  void rootCloseInvalidatesAllViewsAndRejectsLateBorrowing() throws Exception {
    var resource = resource(false, true);
    try (var cursor = resource.openCursor()) {
      assertArrayEquals(bytes, cursor.readAllBytes());
    }
    var snapshot = resource.completedSnapshot().orElseThrow();
    var channel = snapshot.openChannel();
    resource.close();
    assertFalse(resource.hasCompletedSnapshot());
    assertThrows(ClosedChannelException.class, resource::completedSnapshot);
    assertThrows(ClosedChannelException.class, snapshot::length);
    assertThrows(ClosedChannelException.class, snapshot::openChannel);
    assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    assertFalse(channel.isOpen());
    snapshot.close();
    channel.close();
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  @Timeout(10)
  void completedPublicationRacingCursorCancellationNeverBorrowsADeletedFile() throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 100; i++) {
        try (var resource = resource(false, true)) {
          var cursor = resource.openCursor();
          CountDownLatch start = new CountDownLatch(1);
          var read =
              workers.submit(
                  () -> {
                    start.await();
                    try {
                      cursor.readAllBytes();
                    } catch (IOException cancelled) {
                    }
                    return null;
                  });
          var cancel =
              workers.submit(
                  () -> {
                    start.await();
                    cursor.close();
                    return null;
                  });
          start.countDown();
          read.get(2, TimeUnit.SECONDS);
          cancel.get(2, TimeUnit.SECONDS);
          var candidate = resource.completedSnapshot();
          if (candidate.isPresent()) {
            try (var snapshot = candidate.orElseThrow();
                var channel = snapshot.openChannel()) {
              ByteBuffer captured = ByteBuffer.allocate(bytes.length);
              assertEquals(bytes.length, channel.read(captured));
              assertArrayEquals(bytes, captured.array());
            }
          }
        }
      }
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  @Timeout(10)
  void stateInspectionDoesNotWaitForAnActiveSocketRead() throws Exception {
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    URI uri = URI.create("https://example.test/stalled");
    var workers = Executors.newSingleThreadExecutor();
    var resource =
        new CursorMediaResource(
            () ->
                new CursorMediaResource.Attempt() {
                  public MediaTransport.Response open(
                      Long first, MediaTransport.ExpectedRepresentation expected) {
                    return new MediaTransport.Response(
                        uri,
                        200,
                        -1,
                        new InputStream() {
                          public int read() throws IOException {
                            reading.countDown();
                            try {
                              release.await();
                            } catch (InterruptedException e) {
                              throw new IOException(e);
                            }
                            return -1;
                          }
                        });
                  }

                  public void cancel() {
                    release.countDown();
                  }
                },
            directory,
            1024);
    try (resource;
        var cursor = resource.openCursor()) {
      var pending = workers.submit(() -> assertThrows(IOException.class, cursor::read));
      assertTrue(reading.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(1),
          () -> {
            assertFalse(resource.hasCompletedSnapshot());
            assertTrue(resource.completedSnapshot().isEmpty());
          });
      cursor.close();
      pending.get(2, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  @Timeout(10)
  void typedRepresentationChangeCannotPublishACompletedSnapshot() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicBoolean changedOnce = new AtomicBoolean();
    server.createContext(
        "/",
        exchange -> {
          String range = exchange.getRequestHeaders().getFirst("Range");
          boolean ranged = range != null;
          int first = ranged ? Integer.parseInt(range.substring(6, range.length() - 1)) : 0;
          boolean changed = ranged && changedOnce.compareAndSet(false, true);
          exchange.getResponseHeaders().set("ETag", changed ? "\"changed\"" : "\"stable\"");
          if (ranged) exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-7/8");
          exchange.sendResponseHeaders(ranged ? 206 : 200, bytes.length - first);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes, first, bytes.length - first);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var transport = new MediaTransport();
        var resource =
            new CursorMediaResource(
                CursorMediaResource.transport(
                    transport,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    Map.of(),
                    false),
                directory,
                1024);
        var cursor = resource.openCursor()) {
      cursor.readNBytes(2);
      cursor.position(6);
      assertThrows(MediaTransport.ResourceChangedException.class, cursor::read);
      // Even a concurrent cursor subsequently completing the old pinned ranges cannot resurrect it.
      try (var completing = resource.openCursor()) {
        assertArrayEquals(bytes, completing.readAllBytes());
      }
      assertFalse(resource.hasCompletedSnapshot());
      assertTrue(resource.completedSnapshot().isEmpty());
    } finally {
      server.stop(0);
    }
  }
}
