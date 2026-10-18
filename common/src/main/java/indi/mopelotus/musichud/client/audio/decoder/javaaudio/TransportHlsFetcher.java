package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.container.hls.HlsManifest;
import indi.mopelotus.musichud.audio.container.input.HlsPlaybackInput;
import indi.mopelotus.musichud.audio.container.input.PlaylistResolver;
import indi.mopelotus.musichud.client.audio.decoder.transport.MediaTransport;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Bridges each HLS operation to existing security/cancel transport. Does not own the shared
 * transport.
 */
public final class TransportHlsFetcher implements HlsPlaybackInput.Fetcher {
  /**
   * Headers must be selected for this exact URI. Never blindly replay root credentials to
   * segment/key origins.
   */
  @FunctionalInterface
  public interface Headers {
    Map<String, String> forUri(URI uri) throws IOException;
  }

  private final MediaTransport transport;
  private final Headers headers;

  public TransportHlsFetcher(MediaTransport transport, Headers headers) {
    this.transport = Objects.requireNonNull(transport);
    this.headers = Objects.requireNonNull(headers);
  }

  public HlsPlaybackInput.Request request(URI uri, HlsManifest.Range range, boolean publicOnly)
      throws IOException {
    PlaylistResolver.validate(uri, null, publicOnly);
    if (range != null
        && (range.offset() < 0 || range.length() < 1 || range.length() > 16 * 1024 * 1024L))
      throw new IOException("Invalid HLS range");
    if (uri.getScheme().equalsIgnoreCase("file")) {
      if (publicOnly) throw new IOException("Public HLS cannot open local file");
      return new HlsPlaybackInput.Request() {
        private boolean closed;
        private InputStream active;

        public HlsPlaybackInput.Resource open() throws IOException {
          InputStream opened = Files.newInputStream(Path.of(uri));
          synchronized (this) {
            if (closed) {
              opened.close();
              throw new IOException("HLS cancelled");
            }
            active = opened;
          }
          try {
            if (range != null) opened.skipNBytes(range.offset());
            InputStream body = range == null ? opened : new Limited(opened, range.length());
            return new HlsPlaybackInput.Resource() {
              public URI finalUri() {
                return uri;
              }

              public InputStream body() {
                return body;
              }

              public void close() throws IOException {
                body.close();
              }
            };
          } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
          }
        }

        public void close() {
          InputStream old;
          synchronized (this) {
            if (closed) return;
            closed = true;
            old = active;
            active = null;
          }
          if (old != null)
            try {
              old.close();
            } catch (IOException ignored) {
            }
        }
      };
    }
    Map<String, String> selected = Map.copyOf(headers.forUri(uri));
    MediaTransport.Cancellation cancellation = new MediaTransport.Cancellation();
    return new HlsPlaybackInput.Request() {
      public HlsPlaybackInput.Resource open() throws IOException {
        MediaTransport.ByteRange requested = null;
        if (range != null)
          try {
            requested =
                new MediaTransport.ByteRange(
                    range.offset(), Math.addExact(range.offset(), range.length() - 1));
          } catch (ArithmeticException e) {
            throw new IOException("HLS range overflow", e);
          }
        MediaTransport.Response response =
            openHttp(uri, selected, publicOnly, requested, cancellation);
        try {
          if (response.status() != 200 && response.status() != 206)
            throw new IOException("HLS HTTP status " + response.status());
          InputStream decodedBody = response.body();
          if (range != null) {
            if (response.icyMetaInt() > 0)
              throw new IOException("ICY radio cannot serve HLS byte ranges");
            if (response.status() == 200) decodedBody.skipNBytes(range.offset());
            decodedBody = new Limited(decodedBody, range.length());
          }
          InputStream segmentBody = decodedBody;
          return new HlsPlaybackInput.Resource() {
            public URI finalUri() {
              return response.finalUri();
            }

            public InputStream body() {
              return segmentBody;
            }

            public void close() throws IOException {
              response.close();
            }
          };
        } catch (IOException | RuntimeException e) {
          response.close();
          throw e;
        }
      }

      public void close() {
        cancellation.cancel();
      }
    };
  }

  private MediaTransport.Response openHttp(
      URI uri,
      Map<String, String> selected,
      boolean publicOnly,
      MediaTransport.ByteRange range,
      MediaTransport.Cancellation cancellation)
      throws IOException {
    try {
      return transport.open(uri, selected, publicOnly, range, cancellation, null);
    } catch (MediaTransport.ResourceChangedException changed) {
      if (range == null
          || changed.reason() != MediaTransport.ChangeReason.HTTP_STATUS
          || changed.status() != 200) throw changed;
      // The rejected response is already closed by transport. Read one fresh full response only;
      // no bytes from the failed ranged request are joined or cached.
      return transport.open(uri, selected, publicOnly, null, cancellation, null);
    }
  }

  private static final class Limited extends FilterInputStream {
    private long remaining;

    Limited(InputStream input, long remaining) {
      super(input);
      this.remaining = remaining;
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      int n = read(b, 0, 1);
      return n < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int count) throws IOException {
      Objects.checkFromIndexSize(off, count, b.length);
      if (count == 0) return 0;
      if (remaining == 0) return -1;
      int n = in.read(b, off, (int) Math.min(remaining, count));
      if (n < 0) throw new EOFException("Truncated HLS byte range");
      if (n == 0) throw new IOException("Non-progressing HLS byte range");
      remaining -= n;
      return n;
    }

    public long skip(long n) throws IOException {
      if (n <= 0) return 0;
      long skipped = in.skip(Math.min(remaining, n));
      if (skipped > 0) remaining -= skipped;
      return skipped;
    }

    public int available() throws IOException {
      return (int) Math.min(remaining, in.available());
    }
  }
}
