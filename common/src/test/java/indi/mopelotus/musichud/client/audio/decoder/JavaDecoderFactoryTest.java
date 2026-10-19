package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class JavaDecoderFactoryTest {
  private static final List<String> TONES =
      List.of(
          "tone.wav",
          "tone.flac",
          "tone.mp3",
          "tone.opus",
          "tone.m4a",
          "tone.alac.m4a",
          "tone.wv",
          "tone.wma",
          "tone.ape",
          "tone.aiff",
          "tone.au");

  @Test
  void fragmentedOpenEndedFinalEditsPlayAndApplyNonzeroPrimingOnce() throws Exception {
    for (String variant : List.of("hls-input-fmp4-study", "hls-input-fmp4-primed-study")) {
      Path file = Files.createTempFile("java-open-ended-mp4-", ".mp4");
      try {
        var bytes = new ByteArrayOutputStream();
        for (String part : List.of("init.mp4", "media0.m4s", "media1.m4s"))
          try (var in = getClass().getResourceAsStream("/hls-timeline/" + variant + "/" + part)) {
            in.transferTo(bytes);
          }
        Files.write(file, bytes.toByteArray());
        byte[] complete;
        int stride, rate;
        try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
          stride = decoder.getFrameSize();
          rate = decoder.getSampleRate();
          complete = all(decoder);
        }
        assertEquals(variant.contains("primed") ? 62400 : 63424, complete.length / stride);
        try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
          assertTrue(decoder.seekToFrame(rate / 10));
          assertArrayEquals(
              Arrays.copyOfRange(complete, rate / 10 * stride, complete.length), all(decoder));
        }
      } finally {
        Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void exactMp3IndexesReuseLocalOrCompletedResponseBytesWithoutAnotherRequest() throws Exception {
    byte[] encoded = fixture("tone.mp3");
    Path file = Files.createTempFile("java-mp3-index-", ".bin");
    AtomicInteger requests = new AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/audio",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, encoded.length);
          try (var out = exchange.getResponseBody()) {
            out.write(encoded);
          }
        });
    server.start();
    try {
      Files.write(file, encoded);
      for (String identifier :
          List.of(
              file.toString(), "http://127.0.0.1:" + server.getAddress().getPort() + "/audio")) {
        try (var decoder = JavaDecoderFactory.open(identifier, Map.of(), false, true, true)) {
          byte[] complete = all(decoder);
          int before = requests.get(), stride = decoder.getFrameSize();
          long total = complete.length / stride;
          assertTrue(decoder.supportsFastSeek());
          for (long target : new long[] {0, 1, total / 2, total - 1, total + 1000}) {
            assertTrue(decoder.seekToFrame(target));
            assertEquals(target, decoder.getPositionFrames());
            assertArrayEquals(
                target >= total
                    ? new byte[0]
                    : Arrays.copyOfRange(
                        complete, Math.toIntExact(target * stride), complete.length),
                all(decoder));
            assertEquals(
                before, requests.get(), "Exact indexes must not redownload completed bytes");
          }
        }
      }
    } finally {
      server.stop(0);
      Files.deleteIfExists(file);
    }
  }

  @Test
  void sparseHeAacHeadersExpandTheRecoveryWindowBeforePresentingTheTarget() throws Exception {
    Path file = Files.createTempFile("java-aac-sparse-headers-", ".mp4");
    try {
      Files.write(file, fixture("seek-hev2-period72.m4a"));
      byte[] complete;
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        complete = all(decoder);
      }
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        for (long millis : new long[] {3000, 3200}) {
          assertTrue(decoder.seekToMillis(millis));
          long target = millis * decoder.getSampleRate() / 1000;
          assertEquals(target, decoder.getPositionFrames());
          assertArrayEquals(
              Arrays.copyOfRange(
                  complete, Math.toIntExact(target * decoder.getFrameSize()), complete.length),
              all(decoder));
        }
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void aacSeekPreservesThePresentedSampleGridAfterRecovery() throws Exception {
    Path file = Files.createTempFile("java-aac-recovery-", ".mp4");
    try {
      Files.write(file, fixture("seek-lc.m4a"));
      byte[] complete;
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        complete = all(decoder);
      }
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        assertTrue(decoder.supportsFastSeek());
        long target = decoder.getSampleRate() * 24L / 10;
        assertTrue(decoder.seekToFrame(target));
        assertEquals(target, decoder.getPositionFrames());
        byte[] sought = all(decoder);
        byte[] reference =
            Arrays.copyOfRange(
                complete, Math.toIntExact(target * decoder.getFrameSize()), complete.length);
        assertEquals(reference.length, sought.length);
        assertTrue(sought.length > 0);
        var original = java.nio.ByteBuffer.wrap(reference).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var actual = java.nio.ByteBuffer.wrap(sought).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        double energy = 0, error = 0;
        while (original.hasRemaining()) {
          double x = original.getFloat(), y = actual.getFloat();
          energy += x * x;
          error += (x - y) * (x - y);
        }
        assertTrue(10 * Math.log10(energy / Math.max(1e-30, error)) > 70);
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void indexedOggPastTailIsNaturalEofAndRestartable() throws Exception {
    Path file = Files.createTempFile("java-audio-eof-", ".bin");
    try {
      Files.write(file, fixture("tone.opus"));
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        assertTrue(decoder.supportsFastSeek());
        assertTrue(decoder.seekToFrame(10L * decoder.getSampleRate()));
        assertEquals(10L * decoder.getSampleRate(), decoder.getPositionFrames());
        assertNull(decoder.readChunk(4096));
        assertTrue(decoder.seekToFrame(0));
        assertNotNull(decoder.readChunk(4096));
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void localAndHeaderProtectedUnknownExtensionHttpProduceIdenticalWholeFrames() throws Exception {
    for (String name : TONES) {
      byte[] encoded = fixture(name);
      Path file = Files.createTempFile("java-audio-tone-", ".bin");
      Files.write(file, encoded);
      AtomicInteger requests = new AtomicInteger();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/media",
          exchange -> {
            requests.incrementAndGet();
            if (!"fixture".equals(exchange.getRequestHeaders().getFirst("X-Audio-Test"))) {
              exchange.sendResponseHeaders(403, -1);
              exchange.close();
              return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, encoded.length);
            try (var output = exchange.getResponseBody()) {
              output.write(encoded);
            }
          });
      server.start();
      try {
        byte[] local;
        try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
          local = all(decoder);
        }
        try (var decoder =
            JavaDecoderFactory.open(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/media",
                Map.of("X-Audio-Test", "fixture"),
                false,
                true,
                true)) {
          assertArrayEquals(local, all(decoder), name);
        }
        assertEquals(1, requests.get(), name + " must reuse its probing response");
        assertTrue(local.length > 0, name);
        boolean nonzero = false;
        for (byte value : local) nonzero |= value != 0;
        assertTrue(nonzero, name);
      } finally {
        server.stop(0);
        Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void seekMapsPresentationBeforeOpeningReplacement() throws Exception {
    Path file = Files.createTempFile("java-audio-seek-", ".bin");
    try {
      Files.write(file, fixture("tone.m4a"));
      byte[] expected;
      int frameSize, rate;
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        frameSize = decoder.getFrameSize();
        rate = decoder.getSampleRate();
        expected = all(decoder);
      }
      try (var decoder = JavaDecoderFactory.open(file.toString(), Map.of(), false, true, true)) {
        assertTrue(decoder.seekToMillis(100));
        int first = rate / 10 * frameSize;
        byte[] actual = all(decoder);
        assertArrayEquals(Arrays.copyOfRange(expected, first, expected.length), actual);
        assertTrue(decoder.seekToMillis(0));
        assertArrayEquals(expected, all(decoder));
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void nestedLocalM3uAndPlsReuseTheResolvedMedia() throws Exception {
    Path directory = Files.createTempDirectory("java-audio-list-");
    try {
      Files.write(directory.resolve("tone.bin"), fixture("tone.mp3"));
      Files.writeString(directory.resolve("inner.pls"), "[playlist]\nFile1=tone.bin\n");
      Files.writeString(directory.resolve("outer.m3u"), "#EXTM3U\ninner.pls\n");
      try (var direct =
              JavaDecoderFactory.open(
                  directory.resolve("tone.bin").toString(), Map.of(), false, true, true);
          var listed =
              JavaDecoderFactory.open(
                  directory.resolve("outer.m3u").toString(), Map.of(), false, true, true)) {
        assertArrayEquals(all(direct), all(listed));
      }
    } finally {
      Files.deleteIfExists(directory.resolve("outer.m3u"));
      Files.deleteIfExists(directory.resolve("inner.pls"));
      Files.deleteIfExists(directory.resolve("tone.bin"));
      Files.deleteIfExists(directory);
    }
  }

  @Test
  void oggRandomAccessPreservesTheGlobalSampleClockAndWeakSnapshot() throws Exception {
    byte[] encoded = fixture("tone.opus");
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, encoded.length);
          try (var body = exchange.getResponseBody()) {
            body.write(encoded);
          }
        });
    server.start();
    try (var decoder =
        JavaDecoderFactory.open(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            Map.of(),
            false,
            true,
            true)) {
      byte[] expected = all(decoder);
      assertEquals(1, requests.get());
      for (int millis : List.of(20, 80, 20, 0)) {
        if (requests.get() == 1)
          assertFalse(
              decoder.supportsFastSeek(),
              "Cold weak sources must not force a complete download for automatic synchronization");
        assertTrue(decoder.seekToMillis(millis), "seek=" + millis);
        assertTrue(
            decoder.supportsFastSeek(),
            "Completed same-response snapshot permits bounded local seeks");
        assertEquals(millis, decoder.getPositionMillis());
        int byteOffset = millis * decoder.getSampleRate() / 1000 * decoder.getFrameSize();
        assertArrayEquals(Arrays.copyOfRange(expected, byteOffset, expected.length), all(decoder));
        assertEquals(
            2, requests.get(), "Weak source is captured once, then seeks borrow its bytes");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void programmeFactoryPreservesTsDynamicAndFragmentedMp4Presentation() throws Exception {
    for (String name :
        List.of(
            "audio.m3u8",
            "dynamic/dynamic.m3u8",
            "hls-input-fmp4-study/media.m3u8",
            "hls-input-fmp4-primed-study/media.m3u8")) {
      var uri = JavaDecoderFactoryTest.class.getResource("/hls-timeline/" + name).toURI();
      try (var decoder = JavaDecoderFactory.open(uri.toString(), Map.of(), false, true, true)) {
        byte[] expected = all(decoder);
        assertTrue(expected.length > 0, name);
        assertTrue(decoder.seekToMillis(100), name);
        int offset = decoder.getSampleRate() / 10 * decoder.getFrameSize();
        assertArrayEquals(
            Arrays.copyOfRange(expected, offset, expected.length), all(decoder), name);
      }
    }
  }

  @Test
  void physicalOggChainsRetainLaterLinksAndDeclineSingleLinkIndexing() throws Exception {
    for (String second : List.of("tone-second.opus", "tone-second.ogg")) {
      Path chain = Files.createTempFile("java-audio-chain-", ".bin");
      try {
        var encoded = new ByteArrayOutputStream();
        encoded.write(fixture("tone.opus"));
        encoded.write(fixture(second));
        Files.write(chain, encoded.toByteArray());
        try (var decoder = JavaDecoderFactory.open(chain.toString(), Map.of(), false, true, true)) {
          byte[] expected = all(decoder);
          assertTrue(
              expected.length / decoder.getFrameSize() > 48000 / 4,
              "Later logical audio must be retained");
          for (int millis : List.of(20, 150, 220, 0)) {
            assertTrue(decoder.seekToMillis(millis), second + " at " + millis);
            int offset = millis * decoder.getSampleRate() / 1000 * decoder.getFrameSize();
            assertArrayEquals(Arrays.copyOfRange(expected, offset, expected.length), all(decoder));
            assertFalse(
                decoder.supportsFastSeek(), "A physical chain must decline the single-link index");
          }
        }
      } finally {
        Files.deleteIfExists(chain);
      }
    }
  }

  @Test
  void ignoredMp4RangeRestartsOneFreshSingleResponse() throws Exception {
    ignoredMp4RangeRestartsOneFreshSingleResponse(false);
    ignoredMp4RangeRestartsOneFreshSingleResponse(true);
  }

  private void ignoredMp4RangeRestartsOneFreshSingleResponse(boolean changed) throws Exception {
    byte[] original = fixture("tone.m4a");
    int moov = -1;
    for (int offset = 0; offset + 8 <= original.length; ) {
      int size = java.nio.ByteBuffer.wrap(original, offset, 4).getInt();
      if (new String(original, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII)
          .equals("moov")) {
        moov = offset;
        break;
      }
      if (size < 8) throw new IOException("Invalid test MP4");
      offset += size;
    }
    assertTrue(moov > 0);
    var media = new ByteArrayOutputStream();
    media.write(original, 0, moov);
    int padding = 256 * 1024;
    media.write(java.nio.ByteBuffer.allocate(8).putInt(padding).putInt(0x66726565).array());
    media.write(new byte[padding - 8]);
    media.write(original, moov, original.length - moov);
    byte[] encoded = media.toByteArray();
    AtomicInteger requests = new AtomicInteger(), ranges = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          int request = requests.incrementAndGet();
          if (exchange.getRequestHeaders().getFirst("Range") != null) ranges.incrementAndGet();
          exchange.getResponseHeaders().set(
              "ETag", changed && request > 1 ? "\"replacement\"" : "\"stable\"");
          exchange.sendResponseHeaders(200, encoded.length);
          try (var out = exchange.getResponseBody()) {
            out.write(encoded);
          } catch (IOException cancelled) {
          }
        });
    server.start();
    Path local = Files.createTempFile("java-audio-range-", ".bin");
    try {
      Files.write(local, original);
      byte[] expected;
      try (var decoder = JavaDecoderFactory.open(local.toString(), Map.of(), false, true, true)) {
        expected = all(decoder);
      }
      try (var decoder =
          JavaDecoderFactory.open(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/",
              Map.of(),
              false,
              true,
              true)) {
        assertArrayEquals(expected, all(decoder));
      }
      assertEquals(1, ranges.get());
      assertEquals(3, requests.get());
    } finally {
      server.stop(0);
      Files.deleteIfExists(local);
    }
  }

  @Test
  void redirectedPlaylistDoesNotReassignOriginalCredentialsToItsFinalOrigin() throws Exception {
    byte[] encoded = fixture("tone.wav");
    AtomicInteger leaked = new AtomicInteger(), mediaGets = new AtomicInteger();
    var origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var cdn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    cdn.createContext(
        "/list",
        exchange -> {
          byte[] list = "#EXTM3U\nsong\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, list.length);
          try (var out = exchange.getResponseBody()) {
            out.write(list);
          }
        });
    cdn.createContext(
        "/song",
        exchange -> {
          mediaGets.incrementAndGet();
          for (String name : List.of("Cookie", "Authorization", "X-Token"))
            if (exchange.getRequestHeaders().getFirst(name) != null) leaked.incrementAndGet();
          if (!"audio-test".equals(exchange.getRequestHeaders().getFirst("User-Agent")))
            leaked.incrementAndGet();
          exchange.sendResponseHeaders(200, encoded.length);
          try (var out = exchange.getResponseBody()) {
            out.write(encoded);
          }
        });
    origin.createContext(
        "/",
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + cdn.getAddress().getPort() + "/list");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    origin.start();
    cdn.start();
    try (var decoder =
        JavaDecoderFactory.open(
            "http://127.0.0.1:" + origin.getAddress().getPort() + "/",
            Map.of(
                "Cookie",
                "private",
                "Authorization",
                "secret",
                "X-Token",
                "secret",
                "User-Agent",
                "audio-test"),
            false,
            true,
            true)) {
      assertTrue(all(decoder).length > 0);
      assertEquals(1, mediaGets.get());
      assertEquals(0, leaked.get());
    } finally {
      origin.stop(0);
      cdn.stop(0);
    }
  }

  @Test
  void ignoredOggRangeReopensOneIdentityCheckedStreamAndCommitsExactPcm() throws Exception {
    byte[] encoded = fixture("seek-ignored-range.opus");
    byte[] expected;
    Path local = Files.createTempFile("ignored-range-reference-", ".opus");
    Files.write(local, encoded);
    try (var decoder = JavaDecoderFactory.open(local.toString(), Map.of(), false, true, true)) {
      expected = all(decoder);
    } finally { Files.deleteIfExists(local); }
    AtomicInteger gets = new AtomicInteger(), ranges = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", e -> {
      gets.incrementAndGet();
      if (e.getRequestHeaders().getFirst("Range") != null) {
        ranges.incrementAndGet();
        assertEquals("\"same\"", e.getRequestHeaders().getFirst("If-Range"));
      }
      e.getResponseHeaders().set("ETag", "\"same\"");
      e.sendResponseHeaders(200, encoded.length);
      try (var out = e.getResponseBody()) { out.write(encoded); }
      catch (IOException cancelled) {}
    });
    server.start();
    try (var decoder = JavaDecoderFactory.open(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, true, true)) {
      assertNotNull(decoder.readChunk(64));
      assertTrue(decoder.supportsFastSeek());
      long target = decoder.getSampleRate() / 20;
      assertTrue(decoder.seekToFrame(target));
      assertEquals(target, decoder.getPositionFrames());
      assertEquals(1, ranges.get());
      assertEquals(3, gets.get());
      assertFalse(decoder.supportsFastSeek(), "Do not try the ignored Range again");
      assertArrayEquals(Arrays.copyOfRange(expected,
          Math.toIntExact(target * decoder.getFrameSize()), expected.length), all(decoder));
      assertTrue(decoder.seekToFrame(100L * decoder.getSampleRate()));
      assertNull(decoder.readChunk(64), "Beyond-tail sequential fallback remains natural EOF");
      assertTrue(decoder.seekToFrame(0));
      assertArrayEquals(expected, all(decoder));
      assertEquals(1, ranges.get());
    } finally { server.stop(0); }
  }

  @Test
  void ignoredRangeCannotReopenChangedOrUnvalidatedFullBody() throws Exception {
    byte[] encoded = fixture("seek-ignored-range.opus");
    for (String mode : List.of("changed", "missing", "weak", "bad", "duplicate", "changed-on-reopen")) {
      AtomicInteger ranges = new AtomicInteger(), gets = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", e -> {
        int request = gets.incrementAndGet();
        boolean range = e.getRequestHeaders().getFirst("Range") != null;
        if (range) ranges.incrementAndGet();
        String tag = "\"same\"";
        if (range && !mode.equals("changed-on-reopen") || request >= 3) {
          tag = switch (mode) {
            case "missing" -> null;
            case "weak" -> "W/\"same\"";
            case "bad" -> "unquoted";
            case "duplicate" -> "\"same\"";
            default -> "\"changed\"";
          };
        }
        if (tag != null) e.getResponseHeaders().set("ETag", tag);
        if (range && mode.equals("duplicate")) e.getResponseHeaders().add("ETag", "\"other\"");
        e.sendResponseHeaders(200, encoded.length);
        try (var out = e.getResponseBody()) { out.write(encoded); }
        catch (IOException cancelled) {}
      });
      server.start();
      try (var decoder = JavaDecoderFactory.open(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, true, true)) {
        assertNotNull(decoder.readChunk(64));
        assertFalse(decoder.seekToFrame(decoder.getSampleRate() / 20), mode);
        assertEquals(1, ranges.get(), mode);
        assertEquals(mode.equals("changed-on-reopen") ? 3 : 2, gets.get(), mode);
      } finally { server.stop(0); }
    }
  }

  @Test
  @org.junit.jupiter.api.Timeout(15)
  void ignoredRangeReplayDoesNotWaitForTheUnneededTail() throws Exception {
    byte[] encoded = fixture("seek-ignored-range.opus");
    var executor = Executors.newCachedThreadPool();
    CountDownLatch tail = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", e -> {
      int n = requests.incrementAndGet();
      e.getResponseHeaders().set("ETag", "\"same\"");
      e.sendResponseHeaders(200, encoded.length);
      try (var out = e.getResponseBody()) {
        if (n == 3) {
          out.write(encoded, 0, encoded.length / 2); out.flush(); tail.countDown();
          release.await();
          out.write(encoded, encoded.length / 2, encoded.length - encoded.length / 2);
        } else out.write(encoded);
      } catch (IOException cancelled) {
      } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    });
    server.start();
    try (var decoder = JavaDecoderFactory.open(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, true, true)) {
      assertNotNull(decoder.readChunk(64));
      Future<Boolean> seek = executor.submit(() -> decoder.seekToFrame(2400));
      assertTrue(tail.await(3, TimeUnit.SECONDS));
      assertTrue(seek.get(3, TimeUnit.SECONDS), "Target precedes the stalled compressed tail");
      assertEquals(2400, decoder.getPositionFrames());
      assertNotNull(decoder.readChunk(4096));
      assertEquals(3, requests.get());
    } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
  }

  @Test
  @org.junit.jupiter.api.Timeout(20)
  void cancelledOrSupersededRangeFallbackCannotCommitLateOpening() throws Exception {
    byte[] encoded = fixture("seek-ignored-range.opus");
    for (boolean close : List.of(false, true)) {
      var executor = Executors.newCachedThreadPool();
      CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger requests = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", e -> {
        int n = requests.incrementAndGet();
        try {
          if (n == 3) { opening.countDown(); release.await(); }
          e.getResponseHeaders().set("ETag", "\"same\"");
          e.sendResponseHeaders(200, encoded.length);
          try (var out = e.getResponseBody()) { out.write(encoded); }
        } catch (IOException cancelled) {
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      server.start();
      try (var decoder = JavaDecoderFactory.open(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/", Map.of(), false, true, true)) {
        assertNotNull(decoder.readChunk(64));
        Future<Boolean> old = executor.submit(() -> decoder.seekToFrame(2400));
        assertTrue(opening.await(3, TimeUnit.SECONDS));
        if (close) decoder.close();
        else { assertTrue(decoder.seekToFrame(4800)); assertEquals(4800, decoder.getPositionFrames()); }
        assertFalse(old.get(3, TimeUnit.SECONDS));
        release.countDown();
        if (close) assertNull(decoder.readChunk(64));
        else { assertEquals(4800, decoder.getPositionFrames()); assertNotNull(decoder.readChunk(64)); }
      } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
  }

  private static byte[] fixture(String name) throws IOException {
    try (var in = JavaDecoderFactoryTest.class.getResourceAsStream("/javaaudio/" + name)) {
      if (in == null) throw new IOException("Missing synthetic fixture");
      return in.readAllBytes();
    }
  }

  private static byte[] all(AudioDecoder decoder) throws IOException {
    var output = new ByteArrayOutputStream();
    for (int count = 0; count < 10000; count++) {
      byte[] chunk = decoder.readChunk(4096);
      if (chunk == null) return output.toByteArray();
      assertTrue(chunk.length > 0);
      assertEquals(0, chunk.length % decoder.getFrameSize());
      output.write(chunk);
    }
    throw new IOException("Decoder failed to reach EOF");
  }
}
