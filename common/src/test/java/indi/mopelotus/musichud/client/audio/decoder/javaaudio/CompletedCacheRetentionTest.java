package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CompletedCacheRetentionTest {
  @TempDir Path directory;
  private final AtomicInteger requests = new AtomicInteger();

  private CursorMediaResource resource(boolean known) throws IOException {
    URI uri = URI.create("https://example.test/weak-song");
    return new CursorMediaResource(
        () ->
            new CursorMediaResource.Attempt() {
              public MediaTransport.Response open(
                  Long first, MediaTransport.ExpectedRepresentation expected) {
                byte[] body = new byte[16];
                Arrays.fill(body, (byte) requests.incrementAndGet());
                return new MediaTransport.Response(
                    uri,
                    200,
                    known ? 16 : -1,
                    new ByteArrayInputStream(body),
                    new MediaTransport.Representation(
                        uri, known ? 16 : -1, "W/\"weak\"", null, null, true));
              }

              public void cancel() {}
            },
        directory,
        32);
  }

  private void capture(CursorMediaResource resource) throws IOException {
    try (var cursor = resource.openCursor()) {
      assertEquals(16, cursor.readAllBytes().length);
    }
  }

  private long files() throws IOException {
    try (var files = Files.list(directory)) {
      return files.count();
    }
  }

  private void assertBody(CursorMediaResource.CompletedSnapshot snapshot, int value)
      throws IOException {
    try (var channel = snapshot.openChannel()) {
      ByteBuffer bytes = ByteBuffer.allocate(16);
      assertEquals(16, channel.read(bytes));
      for (byte b : bytes.array()) assertEquals((byte) value, b);
    }
  }

  @Test
  void eightCompleteWeakResponsesWithoutBorrowersRetainOnlyTheLatestWithinBudget()
      throws Exception {
    Path sentinel = directory.resolve("cursor-owned-user.cache");
    Files.writeString(sentinel, "keep");
    try (var resource = resource(true)) {
      for (int i = 1; i <= 8; i++) {
        capture(resource);
        assertEquals(2, files()); // one owned cache and the unrelated sentinel
        try (var snapshot = resource.completedSnapshot().orElseThrow()) {
          assertBody(snapshot, i);
        }
        try (var files = Files.list(directory)) {
          for (Path file : files.toList())
            if (!file.equals(sentinel)) assertEquals(16, Files.size(file));
        }
      }
    }
    assertEquals(1, files());
    assertEquals("keep", Files.readString(sentinel));
  }

  @Test
  void replacingLatestPreservesAnActiveOldCursorUntilItCloses() throws Exception {
    try (var resource = resource(true)) {
      var old = resource.openCursor();
      old.readAllBytes();
      capture(resource);
      assertEquals(2, files());
      old.position(0);
      byte[] original = old.readAllBytes();
      for (byte b : original) assertEquals(1, b);
      old.close();
      assertEquals(1, files());
      try (var latest = resource.completedSnapshot().orElseThrow()) {
        assertBody(latest, 2);
      }
    }
    assertEquals(0, files());
  }

  @Test
  void oldSnapshotsOwnTheirViewsUntilAllSnapshotBorrowersReleaseTheCache() throws Exception {
    try (var resource = resource(true)) {
      capture(resource);
      var first = resource.completedSnapshot().orElseThrow();
      var second = resource.completedSnapshot().orElseThrow();
      var firstView = first.openChannel();
      capture(resource);
      assertEquals(2, files());
      assertBody(first, 1);
      first.close();
      assertFalse(firstView.isOpen());
      assertThrows(ClosedChannelException.class, () -> firstView.read(ByteBuffer.allocate(1)));
      firstView.close();
      assertEquals(2, files());
      assertBody(second, 1);
      second.close();
      second.close();
      assertEquals(1, files());
      try (var latest = resource.completedSnapshot().orElseThrow()) {
        assertBody(latest, 2);
      }
    }
    assertEquals(0, files());
  }

  @Test
  void unknownLengthEofPublishesAndReplacesOnlyOneLatestCompletedOwner() throws Exception {
    try (var resource = resource(false)) {
      try (var first = resource.openCursor()) {
        assertEquals(16, first.readNBytes(16).length);
        assertFalse(resource.hasCompletedSnapshot());
        assertEquals(-1, first.read());
      }
      for (int i = 2; i <= 8; i++) {
        capture(resource);
        assertEquals(1, files());
        try (var latest = resource.completedSnapshot().orElseThrow()) {
          assertBody(latest, i);
        }
      }
    }
    assertEquals(0, files());
  }

  @Test
  void failedRetirementRemainsOwnedForRootCloseToRetry() throws Exception {
    var resource = resource(true);
    try {
      capture(resource);
      Path previous;
      try (var files = Files.list(directory)) {
        previous = files.findFirst().orElseThrow();
      }
      // Simulate an OS deletion failure without exposing or changing the public cache API.
      Files.delete(previous);
      Files.createDirectory(previous);
      Path obstruction =
          Files.writeString(previous.resolve("block-delete"), "owned-test-obstruction");
      assertThrows(IOException.class, () -> capture(resource));
      assertEquals(2, files());
      Files.delete(obstruction);
      resource.close();
      assertEquals(0, files());
    } finally {
      resource.close();
    }
  }

  @Test
  @Timeout(15)
  void initializationRacingCancellationNeverLeavesAPartialCacheOrLateReference() throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 100; i++) {
        try (var resource = resource(true)) {
          var cursor = resource.newCursor();
          CountDownLatch start = new CountDownLatch(1);
          var initialize =
              workers.submit(
                  () -> {
                    start.await();
                    try {
                      cursor.initialize();
                    } catch (IOException cancelled) {
                    }
                    return null;
                  });
          var close =
              workers.submit(
                  () -> {
                    start.await();
                    cursor.close();
                    return null;
                  });
          start.countDown();
          initialize.get(2, TimeUnit.SECONDS);
          close.get(2, TimeUnit.SECONDS);
          assertFalse(resource.hasCompletedSnapshot());
          assertEquals(0, files());
        }
      }
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  @Timeout(15)
  void concurrentCaptureReplacementBorrowAndRootCloseNeverDeleteAnActiveBorrow() throws Exception {
    var workers = Executors.newFixedThreadPool(3);
    var resource = resource(true);
    AtomicBoolean closing = new AtomicBoolean();
    CountDownLatch published = new CountDownLatch(1);
    try {
      capture(resource);
      Future<?> publisher =
          workers.submit(
              () -> {
                for (int i = 0; i < 100; i++) {
                  try {
                    capture(resource);
                  } catch (IOException closed) {
                    if (!closing.get()) throw new UncheckedIOException(closed);
                    break;
                  }
                  if (i == 10) published.countDown();
                }
              });
      Future<?> borrower =
          workers.submit(
              () -> {
                for (int i = 0; i < 300; i++) {
                  try {
                    var captured = resource.completedSnapshot();
                    if (captured.isEmpty()) continue;
                    try (var snapshot = captured.orElseThrow();
                        var channel = snapshot.openChannel()) {
                      ByteBuffer body = ByteBuffer.allocate(16);
                      assertEquals(16, channel.read(body));
                      byte first = body.array()[0];
                      for (byte value : body.array()) assertEquals(first, value);
                    }
                  } catch (IOException closed) {
                    if (!closing.get()) throw new UncheckedIOException(closed);
                    break;
                  }
                }
              });
      assertTrue(published.await(3, TimeUnit.SECONDS));
      closing.set(true);
      resource.close();
      publisher.get(5, TimeUnit.SECONDS);
      borrower.get(5, TimeUnit.SECONDS);
      assertFalse(resource.hasCompletedSnapshot());
      assertThrows(ClosedChannelException.class, resource::completedSnapshot);
      assertEquals(0, files());
    } finally {
      closing.set(true);
      resource.close();
      workers.shutdownNow();
    }
  }
}
