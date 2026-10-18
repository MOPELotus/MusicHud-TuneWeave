package indi.mopelotus.musichud.client.audio.decoder.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class MediaRepresentationTest {
  @Test
  void strongValidatorPinsRangesEvenWhenThe206OmitsRepeatedMetadata() throws Exception {
    try (var source = new Source();
        var transport = transport()) {
      var expected = initial(source, transport);
      source.mode = Mode.NO_ECHO;
      try (var response = range(source, transport, expected)) {
        assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), response.body().readAllBytes());
        assertEquals(10, response.representation().totalLength());
        assertEquals("\"v1\"", source.ifRange.get());
        assertEquals("identity", source.acceptEncoding.get());
      }
    }
  }

  @Test
  void sameLengthReplacementCannotBeJoinedToCachedBytes() throws Exception {
    try (var source = new Source();
        var transport = transport()) {
      var expected = initial(source, transport);
      source.mode = Mode.NEW_TAG;
      var failure =
          assertThrows(
              MediaTransport.ResourceChangedException.class,
              () -> range(source, transport, expected));
      assertEquals(MediaTransport.ChangeReason.VALIDATOR, failure.reason());
    }
  }

  @Test
  void fullResponseAndPreconditionStatusesRequireWholeSourceReopen() throws Exception {
    for (Mode mode : List.of(Mode.FULL, Mode.PRECONDITION, Mode.UNSATISFIABLE)) {
      try (var source = new Source();
          var transport = transport()) {
        var expected = initial(source, transport);
        source.mode = mode;
        var failure =
            assertThrows(
                MediaTransport.ResourceChangedException.class,
                () -> range(source, transport, expected));
        assertEquals(MediaTransport.ChangeReason.HTTP_STATUS, failure.reason());
        assertEquals(
            mode == Mode.FULL ? 200 : mode == Mode.PRECONDITION ? 412 : 416, failure.status());
      }
    }
  }

  @Test
  void changedTotalOrFinalUriRequiresWholeSourceReopen() throws Exception {
    for (Mode mode : List.of(Mode.NEW_TOTAL, Mode.REDIRECT)) {
      try (var source = new Source();
          var transport = transport()) {
        var expected = initial(source, transport);
        source.mode = mode;
        var failure =
            assertThrows(
                MediaTransport.ResourceChangedException.class,
                () -> range(source, transport, expected));
        assertEquals(
            mode == Mode.NEW_TOTAL
                ? MediaTransport.ChangeReason.TOTAL_LENGTH
                : MediaTransport.ChangeReason.FINAL_URI,
            failure.reason());
      }
    }
  }

  @Test
  void missingOrWeakInitialValidatorsKeepTheOriginalSingleResponseReadable() throws Exception {
    for (String tag : Arrays.asList(null, "W/\"v1\"")) {
      try (var source = new Source();
          var transport = transport()) {
        source.tag = tag;
        source.modified = Instant.now().minusSeconds(120);
        if (tag == null) source.modified = null;
        try (var response = get(source, transport)) {
          assertFalse(response.representation().canRange());
          assertThrows(IllegalStateException.class, response.representation()::expected);
          assertEquals(
              "0123456789", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(source.ifRange.get());
      }
    }
  }

  @Test
  void dateFallbackRequiresNoEntityTagAndUsesValidatedHttpDate() throws Exception {
    try (var source = new Source();
        var transport = transport()) {
      source.tag = null;
      source.modified = Instant.now().minusSeconds(120);
      var expected = initial(source, transport);
      assertInstanceOf(MediaTransport.ModifiedAt.class, expected.validator());
      source.mode = Mode.NO_ECHO;
      try (var response = range(source, transport, expected)) {
        assertEquals("2345", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(expected.validator().headerValue(), source.ifRange.get());
      }
      source.mode = Mode.NEW_DATE;
      assertThrows(
          MediaTransport.ResourceChangedException.class, () -> range(source, transport, expected));
    }
  }

  @Test
  void dateClockMarginAndInvalidMetadataCannotEnableUnsafeRanges() throws Exception {
    for (String tag : Arrays.asList(null, "invalid-tag", "\"" + "x".repeat(2048) + "\"")) {
      try (var source = new Source();
          var transport = transport()) {
        source.tag = tag;
        source.modified = tag == null ? Instant.now() : Instant.now().minusSeconds(120);
        try (var response = get(source, transport)) {
          assertFalse(response.representation().canRange());
          assertEquals(10, response.body().readAllBytes().length);
        }
      }
    }
  }

  @Test
  void duplicateOrMalformedRangeValidatorCannotValidateAChunk() throws Exception {
    for (Mode mode : List.of(Mode.DUPLICATE_TAG, Mode.BAD_TAG, Mode.DUPLICATE_RANGE)) {
      try (var source = new Source();
          var transport = transport()) {
        var expected = initial(source, transport);
        source.mode = mode;
        assertThrows(IOException.class, () -> range(source, transport, expected));
      }
    }
  }

  @Test
  void gzipLengthIsNotAnIdentityRepresentationLength() throws Exception {
    try (var source = new Source();
        var transport = transport()) {
      source.mode = Mode.GZIP;
      try (var response = get(source, transport)) {
        assertEquals(-1, response.length());
        assertEquals(-1, response.representation().totalLength());
        assertFalse(response.representation().identityEncoded());
        assertFalse(response.representation().canRange());
        assertEquals(
            "0123456789", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void typedIfRangeCannotInjectArbitraryHeadersOrUseWeakTags() throws Exception {
    for (String bad :
        Arrays.asList(
            null,
            "W/\"x\"",
            "\"x\r\nInjected: yes\"",
            "\"contains space\"",
            "\"x\u0100\"",
            "*",
            "\"" + "x".repeat(1024) + "\""))
      assertThrows(IllegalArgumentException.class, () -> new MediaTransport.StrongETag(bad));
    assertEquals("\"\"", new MediaTransport.StrongETag("\"\"").headerValue());
    try (var source = new Source();
        var transport = transport()) {
      assertThrows(
          IOException.class,
          () ->
              transport.open(
                  source.uri(),
                  Map.of("If-Range", "\"x\""),
                  false,
                  new MediaTransport.ByteRange(2L, 5L),
                  new MediaTransport.Cancellation()));
      var expected = initial(source, transport);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transport.open(
                  source.uri(),
                  Map.of(),
                  false,
                  null,
                  new MediaTransport.Cancellation(),
                  expected));
    }
  }

  @Test
  void malformedOptionalDatesDisableRangesWithoutRejectingOrdinaryPlayback() throws Exception {
    for (String invalid :
        List.of("not a date", "Mon, 31 Feb 2026 00:00:00 GMT", "Mon, 01 Jan 0001 00:00:00 GMT")) {
      try (var source = new Source();
          var transport = transport()) {
        source.server.createContext(
            "/bad-date",
            exchange -> {
              exchange.getResponseHeaders().set("Last-Modified", invalid);
              byte[] body = "ordinary-media".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
        try (var response =
            transport.open(
                source.uri().resolve("/bad-date"),
                Map.of(),
                false,
                null,
                new MediaTransport.Cancellation())) {
          assertNull(response.representation().lastModified());
          assertFalse(response.representation().canRange());
          assertEquals(
              "ordinary-media", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new MediaTransport.ModifiedAt(Instant.MAX));
  }

  private enum Mode {
    NORMAL,
    NO_ECHO,
    NEW_TAG,
    FULL,
    PRECONDITION,
    UNSATISFIABLE,
    NEW_TOTAL,
    REDIRECT,
    NEW_DATE,
    DUPLICATE_TAG,
    BAD_TAG,
    DUPLICATE_RANGE,
    GZIP
  }

  private static MediaTransport transport() throws Exception {
    return new MediaTransport(
        host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")},
        SSLContext.getDefault(),
        1000);
  }

  private static MediaTransport.Response get(Source source, MediaTransport transport)
      throws Exception {
    return transport.open(source.uri(), Map.of(), false, null, new MediaTransport.Cancellation());
  }

  private static MediaTransport.ExpectedRepresentation initial(
      Source source, MediaTransport transport) throws Exception {
    try (var response = get(source, transport)) {
      assertTrue(response.representation().canRange());
      assertEquals(10, response.representation().totalLength());
      return response.representation().expected();
    }
  }

  private static MediaTransport.Response range(
      Source source, MediaTransport transport, MediaTransport.ExpectedRepresentation expected)
      throws Exception {
    return transport.open(
        source.uri(),
        Map.of(),
        false,
        new MediaTransport.ByteRange(2L, 5L),
        new MediaTransport.Cancellation(),
        expected);
  }

  private static final class Source implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final AtomicReference<String> ifRange = new AtomicReference<>(),
        acceptEncoding = new AtomicReference<>();
    volatile String tag = "\"v1\"";
    volatile Instant modified;
    volatile Mode mode = Mode.NORMAL;

    Source() throws IOException {
      server.createContext("/", this::serve);
      server.start();
    }

    URI uri() {
      return URI.create("http://media.test:" + server.getAddress().getPort() + "/media");
    }

    private void serve(HttpExchange exchange) throws IOException {
      boolean range = exchange.getRequestHeaders().containsKey("Range");
      ifRange.set(exchange.getRequestHeaders().getFirst("If-Range"));
      acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
      if (range && mode == Mode.REDIRECT && exchange.getRequestURI().getPath().equals("/media")) {
        exchange.getResponseHeaders().set("Location", "/moved");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
        return;
      }
      if (range && (mode == Mode.PRECONDITION || mode == Mode.UNSATISFIABLE)) {
        exchange.sendResponseHeaders(mode == Mode.PRECONDITION ? 412 : 416, -1);
        exchange.close();
        return;
      }
      if (!(range && mode == Mode.NO_ECHO)) {
        if (tag != null)
          exchange
              .getResponseHeaders()
              .set(
                  "ETag",
                  range && mode == Mode.NEW_TAG
                      ? "\"v2\""
                      : range && mode == Mode.BAD_TAG ? "unquoted" : tag);
        if (range && mode == Mode.DUPLICATE_TAG)
          exchange.getResponseHeaders().add("ETag", "\"other\"");
        if (modified != null)
          exchange
              .getResponseHeaders()
              .set(
                  "Last-Modified",
                  MediaValidators.formatDate(
                      range && mode == Mode.NEW_DATE ? modified.plusSeconds(1) : modified));
      }
      byte[] body =
          (range && mode == Mode.NEW_TAG ? "abcdefghij" : "0123456789")
              .getBytes(StandardCharsets.UTF_8);
      int status = 200;
      if (range && mode != Mode.FULL) {
        body = Arrays.copyOfRange(body, 2, 6);
        status = 206;
        exchange
            .getResponseHeaders()
            .set("Content-Range", "bytes 2-5/" + (mode == Mode.NEW_TOTAL ? 11 : 10));
        if (mode == Mode.DUPLICATE_RANGE)
          exchange.getResponseHeaders().add("Content-Range", "bytes 2-5/11");
      }
      if (!range && mode == Mode.GZIP) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(compressed)) {
          zip.write(body);
        }
        body = compressed.toByteArray();
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
      }
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
