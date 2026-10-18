package indi.mopelotus.musichud.client.audio.decoder;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
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
          requests.incrementAndGet();
          if (exchange.getRequestHeaders().getFirst("Range") != null) ranges.incrementAndGet();
          exchange.getResponseHeaders().set("ETag", "\"stable\"");
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
