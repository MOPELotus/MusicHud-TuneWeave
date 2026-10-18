package indi.mopelotus.musichud.client.audio.decoder.transport;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Optional untrusted cache validators never become arbitrary caller-supplied HTTP fields. */
final class MediaValidators {
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
          .withResolverStyle(ResolverStyle.STRICT)
          .withZone(ZoneOffset.UTC);

  record Observation(
      MediaTransport.Representation representation,
      boolean entityTagPresent,
      boolean entityTagValid,
      boolean lastModifiedPresent,
      boolean lastModifiedValid) {}

  private MediaValidators() {}

  static boolean validEntityTag(String value) {
    if (value == null || value.length() > 1024) return false;
    int start = value.startsWith("W/") ? 2 : 0;
    if (value.length() < start + 2
        || value.charAt(start) != '"'
        || value.charAt(value.length() - 1) != '"') return false;
    for (int i = start + 1; i < value.length() - 1; i++) {
      char c = value.charAt(i);
      if (c < 0x21 || c == '"' || c == 0x7f || c > 0xff) return false;
    }
    return true;
  }

  static Instant checkedDate(Instant value) {
    Objects.requireNonNull(value);
    try {
      int year = value.atOffset(ZoneOffset.UTC).getYear();
      if (year < 1000 || year > 9999)
        throw new IllegalArgumentException("Invalid HTTP validator date");
      return value.truncatedTo(ChronoUnit.SECONDS);
    } catch (DateTimeException error) {
      throw new IllegalArgumentException("Invalid HTTP validator date");
    }
  }

  static String formatDate(Instant value) {
    return HTTP_DATE.format(checkedDate(value));
  }

  private static Instant date(List<String> values) {
    if (values.size() != 1) return null;
    String value = values.getFirst();
    if (value.length() != 29) return null;
    try {
      return checkedDate(Instant.from(HTTP_DATE.parse(value)));
    } catch (DateTimeException | IllegalArgumentException error) {
      return null;
    }
  }

  static Observation read(URI uri, HttpHeaders headers, long totalLength, boolean identity) {
    List<String> tags = headers.getAll(HttpHeaderNames.ETAG);
    String tag = tags.size() == 1 && validEntityTag(tags.getFirst()) ? tags.getFirst() : null;
    List<String> modified = headers.getAll(HttpHeaderNames.LAST_MODIFIED);
    Instant lastModified = date(modified),
        responseDate = date(headers.getAll(HttpHeaderNames.DATE));
    // An invalid present ETag must not be mistaken for absence and enable date If-Range.
    boolean tagValid = tags.size() == 1 && tag != null;
    Instant eligibleModified = !tags.isEmpty() && !tagValid ? null : lastModified;
    return new Observation(
        new MediaTransport.Representation(
            uri, totalLength, tag, eligibleModified, responseDate, identity),
        !tags.isEmpty(),
        tagValid,
        !modified.isEmpty(),
        modified.size() == 1 && lastModified != null);
  }
}
