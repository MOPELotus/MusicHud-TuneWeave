package indi.mopelotus.musichud.client.audio.decoder.transport;

import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.*;

/**
 * Streaming HTTP media using the host codec and TLS, with connection-level public-address checks.
 */
public final class MediaTransport implements AutoCloseable {
  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  public record ByteRange(long first, Long last) {
    public ByteRange {
      if (first < 0 || last != null && last < first)
        throw new IllegalArgumentException("Invalid byte range");
    }

    String header() {
      return "bytes=" + first + "-" + (last == null ? "" : last);
    }
  }

  public static final class Cancellation {
    private boolean cancelled;
    private final Set<Channel> channels = Collections.newSetFromMap(new IdentityHashMap<>());

    synchronized void attach(Channel value) throws IOException {
      if (cancelled) {
        value.close();
        throw new IOException("Media request cancelled");
      }
      channels.add(value);
    }

    synchronized void detach(Channel value) {
      channels.remove(value);
    }

    public synchronized void cancel() {
      cancelled = true;
      for (Channel channel : channels) channel.close();
      channels.clear();
    }

    synchronized void check() throws IOException {
      if (cancelled) throw new IOException("Media request cancelled");
    }
  }

  public record Response(URI finalUri, int status, long length, InputStream body)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      body.close();
    }
  }

  private static final Set<String> PUBLIC_HEADERS =
      Set.of("accept", "accept-language", "origin", "referer", "user-agent");
  private static final Set<String> RESERVED =
      Set.of(
          "host",
          "connection",
          "content-length",
          "transfer-encoding",
          "range",
          "proxy-authorization",
          "proxy-connection",
          "upgrade",
          "expect",
          "te",
          "trailer",
          "accept-encoding");
  private final EventLoopGroup group =
      new NioEventLoopGroup(
          1, Thread.ofPlatform().name("musichud-media-", 0).daemon(true).factory());
  // OS DNS may ignore interruption. Keep both active calls and queued work bounded.
  private final ThreadPoolExecutor dnsExecutor =
      new ThreadPoolExecutor(
          2,
          2,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(32),
          Thread.ofPlatform().name("media-dns-", 0).daemon(true).factory());

  private final Resolver resolver;
  private final SSLContext tls;
  private final int timeoutMillis;

  public MediaTransport() throws IOException {
    this(InetAddress::getAllByName, defaultTls(), 10_000);
  }

  MediaTransport(Resolver resolver, SSLContext tls, int timeoutMillis) {
    if (timeoutMillis <= 0) throw new IllegalArgumentException("Invalid timeout");
    this.resolver = Objects.requireNonNull(resolver);
    this.tls = Objects.requireNonNull(tls);
    this.timeoutMillis = timeoutMillis;
  }

  private static SSLContext defaultTls() throws IOException {
    try {
      return SSLContext.getDefault();
    } catch (Exception e) {
      throw new IOException("TLS unavailable", e);
    }
  }

  public Response open(
      URI uri,
      Map<String, String> headers,
      boolean publicOnly,
      ByteRange range,
      Cancellation cancellation)
      throws IOException {
    Objects.requireNonNull(cancellation);
    Map<String, String> safe = validateHeaders(headers, publicOnly);
    long openingDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    URI current = uri;
    boolean sensitiveAllowed = true;
    for (int redirect = 0; redirect <= 5; redirect++) {
      cancellation.check();
      validateUri(current, publicOnly);
      InetAddress[] addresses =
          resolve(current.getHost(), publicOnly, cancellation, openingDeadline);
      if (addresses.length == 0) throw new UnknownHostException("No media addresses");
      // No unresolved socket address reaches Netty: this exact validated address is connected.
      Response response =
          connect(current, safe, range, cancellation, addresses, sensitiveAllowed, openingDeadline);
      if (!Set.of(301, 302, 303, 307, 308).contains(response.status())) {
        Body owner = ((BodyStream) response.body()).owner;
        try {
          InputStream decoded =
              switch (owner.encoding) {
                case "gzip", "x-gzip" -> new GZIPInputStream(response.body());
                case "deflate" -> inflate(response.body());
                default -> response.body();
              };
          owner.opening = false;
          return new Response(
              response.finalUri(),
              response.status(),
              decoded == response.body() ? response.length() : -1L,
              decoded);
        } catch (IOException | RuntimeException error) {
          try {
            response.close();
          } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
          }
          throw error;
        }
      }
      String location = ((BodyStream) response.body()).owner.location;
      response.close();
      if (redirect == 5) throw new IOException("Too many media redirects");
      if (location == null) throw new IOException("Missing media redirect location");
      URI next;
      try {
        next = current.resolve(location);
      } catch (IllegalArgumentException e) {
        throw new IOException("Malformed media redirect");
      }
      validateUri(next, publicOnly);
      if (!sameOrigin(current, next)) sensitiveAllowed = false;
      current = next;
    }
    throw new IOException("Too many media redirects");
  }

  private InetAddress[] resolve(
      String host, boolean publicOnly, Cancellation cancellation, long deadline)
      throws IOException {
    cancellation.check();
    Future<InetAddress[]> future;
    try {
      future =
          dnsExecutor.submit(
              () ->
                  publicOnly
                      ? SharedResourceValidator.resolvePublicAddresses(host)
                      : resolver.resolve(host));
    } catch (RejectedExecutionException e) {
      throw new IOException("Media DNS capacity exhausted", e);
    }
    try {
      while (true) {
        cancellation.check();
        if (dnsExecutor.isShutdown()) throw new IOException("Media transport closed");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Media DNS timeout");
        try {
          return future.get(
              Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
        } catch (TimeoutException pending) {
          /* Recheck cancellation and phase deadline. */
        } catch (ExecutionException e) {
          throw new IOException("Media DNS resolution failed", e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Media DNS interrupted", e);
        }
      }
    } finally {
      future.cancel(true);
      dnsExecutor.purge();
    }
  }

  private Response connect(
      URI uri,
      Map<String, String> headers,
      ByteRange range,
      Cancellation cancellation,
      InetAddress[] addresses,
      boolean sensitiveAllowed,
      long openingDeadline)
      throws IOException {
    IOException last = null;
    for (InetAddress address : addresses) {
      cancellation.check();
      Body body = new Body(cancellation, timeoutMillis, range, openingDeadline);
      final boolean secure = uri.getScheme().equalsIgnoreCase("https");
      int port = port(uri);
      Bootstrap bootstrap =
          new Bootstrap()
              .group(group)
              .channel(NioSocketChannel.class)
              .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
              .option(ChannelOption.AUTO_READ, false)
              .handler(
                  new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel channel) {
                      if (secure) {
                        SSLEngine engine = tls.createSSLEngine(uri.getHost(), port);
                        engine.setUseClientMode(true);
                        SSLParameters parameters = engine.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        // SSLEngine uses original DNS host for SNI and hostname verification, while
                        // TCP uses address.
                        if (uri.getHost().indexOf(':') < 0 && !uri.getHost().matches("[0-9.]+"))
                          parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                        engine.setSSLParameters(parameters);
                        channel.pipeline().addLast(new SslHandler(engine));
                      }
                      channel.pipeline().addLast(new HttpClientCodec(4096, 16384, 8192));
                      channel.pipeline().addLast(body);
                    }
                  });
      ChannelFuture connected = bootstrap.connect(new InetSocketAddress(address, port));
      Channel channel = connected.channel();
      body.channel = channel;
      cancellation.attach(channel);
      boolean requestSent = false;
      try {
        if (!connected.await(remainingMillis(openingDeadline)))
          throw new IOException("Media connect timeout");
        if (!connected.isSuccess())
          throw new IOException("Media connect failed", connected.cause());
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        DefaultFullHttpRequest request =
            new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path, Unpooled.EMPTY_BUFFER);
        String host = uri.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        if (port != (secure ? 443 : 80)) host += ":" + port;
        request
            .headers()
            .set(HttpHeaderNames.HOST, host)
            .set(HttpHeaderNames.CONNECTION, "close")
            .set(HttpHeaderNames.USER_AGENT, "MusicHud-TuneWeave")
            .set(HttpHeaderNames.ACCEPT_ENCODING, range == null ? "gzip, deflate" : "identity");
        headers.forEach(
            (name, value) -> {
              if (sensitiveAllowed || !Set.of("authorization", "cookie").contains(name))
                request.headers().set(name, value);
            });
        if (range != null) request.headers().set(HttpHeaderNames.RANGE, range.header());
        requestSent = true;
        channel.writeAndFlush(request);
        channel.read();
        Header header = body.header.get(remainingMillis(openingDeadline), TimeUnit.MILLISECONDS);
        if (header.status != 200
            && header.status != 206
            && !Set.of(301, 302, 303, 307, 308).contains(header.status))
          throw new IOException("Media HTTP status " + header.status);
        return new Response(uri, header.status, header.length, new BodyStream(body));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        body.close();
        throw new IOException("Media interrupted", e);
      } catch (ExecutionException | TimeoutException e) {
        body.close();
        throw new IOException("Media response failed", e);
      } catch (IOException | RuntimeException e) {
        body.close();
        last = e instanceof IOException io ? io : new IOException("Invalid media response", e);
        if (requestSent) throw last;
      }
    }
    throw last == null ? new IOException("Media connect failed") : last;
  }

  private static final class BodyStream extends FilterInputStream {
    final Body owner;

    BodyStream(Body owner) {
      super(owner.input);
      this.owner = owner;
    }
  }

  private record Header(int status, long length) {}

  private static final class Body extends ChannelInboundHandlerAdapter {
    private static final Object END = new Object();
    final CompletableFuture<Header> header = new CompletableFuture<>();
    final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(128);
    final Cancellation cancellation;
    final int timeoutMillis;
    final ByteRange range;
    volatile Channel channel;
    volatile boolean closed, ended;
    boolean interim;
    String location;
    String encoding = "identity";
    final long openingDeadline;
    volatile boolean opening = true;
    long received, expected = -1;

    Body(Cancellation cancellation, int timeoutMillis, ByteRange range, long openingDeadline) {
      this.cancellation = cancellation;
      this.timeoutMillis = timeoutMillis;
      this.range = range;
      this.openingDeadline = openingDeadline;
    }

    byte[] current;
    int offset;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
      try {
        if (message instanceof HttpObject object && !object.decoderResult().isSuccess())
          throw new IOException("Malformed HTTP message", object.decoderResult().cause());
        if (message instanceof HttpResponse response) {
          int status = response.status().code();
          if (status >= 100 && status < 200) {
            if (status == 101) throw new IOException("Media protocol upgrade rejected");
            interim = true;
            return;
          }
          interim = false;
          if (header.isDone()) throw new IOException("Duplicate media response");
          location = response.headers().get(HttpHeaderNames.LOCATION);
          encoding =
              response
                  .headers()
                  .get(HttpHeaderNames.CONTENT_ENCODING, "identity")
                  .trim()
                  .toLowerCase(Locale.ROOT);
          if (!Set.of("identity", "gzip", "x-gzip", "deflate").contains(encoding)
              || range != null && !encoding.equals("identity"))
            throw new IOException("Unexpected media content encoding");
          expected = HttpUtil.getContentLength(response, -1);
          if (expected < -1) throw new IOException("Invalid media length");
          if (status == 206) {
            if (range == null) throw new IOException("Unsolicited partial media response");
            String value = response.headers().get(HttpHeaderNames.CONTENT_RANGE);
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
                    .matcher(value == null ? "" : value);
            if (!m.matches()) throw new IOException("Malformed content range");
            long first = Long.parseLong(m.group(1)), last = Long.parseLong(m.group(2));
            if (first != range.first
                || last < first
                || range.last != null && last > range.last
                || last - first == Long.MAX_VALUE) throw new IOException("Wrong content range");
            long length = last - first + 1;
            if (expected != -1 && length != expected)
              throw new IOException("Content range length mismatch");
            if (!m.group(3).equals("*") && Long.parseLong(m.group(3)) <= last)
              throw new IOException("Invalid content range total");
            expected = length;
          } else if (status == 200 && range != null && (range.first > 0 || range.last != null))
            throw new IOException("Media server ignored range");
          header.complete(new Header(status, expected));
        }
        if (message instanceof HttpContent content) {
          if (interim) return;
          if (!header.isDone()) throw new IOException("Body before media response");
          int length = content.content().readableBytes();
          received = Math.addExact(received, length);
          if (expected >= 0 && received > expected)
            throw new IOException("Media body exceeds length");
          if (length > 0) {
            byte[] bytes = new byte[length];
            content.content().readBytes(bytes);
            if (!queue.offer(bytes)) throw new IOException("Media backpressure limit exceeded");
          }
          if (message instanceof LastHttpContent) {
            if (expected >= 0 && received != expected)
              throw new IOException("Truncated media body");
            ended = true;
            offer(END);
            ctx.close();
          }
        }
      } catch (Exception e) {
        fail(e);
        ctx.close();
      } finally {
        ReferenceCountUtil.release(message);
      }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      if (!header.isDone() && !closed && !ended) ctx.read();
    }

    private void offer(Object value) {
      if (!queue.offer(value)) {
        queue.clear();
        queue.offer(new IOException("Media queue exceeded"));
      }
    }

    private void fail(Throwable error) {
      IOException io = error instanceof IOException i ? i : new IOException("Media failed", error);
      header.completeExceptionally(io);
      // A reader observing ended must already be able to consume the failure.
      offer(io);
      ended = true;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      fail(cause);
      ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      if (!ended && !closed) fail(new IOException("Media connection closed early"));
    }

    // Stream wrapper stays separate from the channel handler (InputStream is a class).
    private final InputStream input =
        new InputStream() {
          @Override
          public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : Byte.toUnsignedInt(b[0]);
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            cancellation.check();
            if (opening) remainingMillis(openingDeadline);
            if (closed) throw new IOException("Media stream closed");
            while (current == null || offset == current.length) {
              current = null;
              offset = 0;
              Object item = queue.poll();
              if (item == null) {
                if (ended) return -1;
                channel.read();
                try {
                  item =
                      queue.poll(
                          opening
                              ? Math.min(timeoutMillis, remainingMillis(openingDeadline))
                              : timeoutMillis,
                          TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  close();
                  throw new IOException("Media interrupted", e);
                }
              }
              cancellation.check();
              if (closed) throw new IOException("Media stream closed");
              if (item == null) {
                close();
                throw new IOException("Media read timeout");
              }
              if (item == END) return -1;
              if (item instanceof IOException e) throw e;
              current = (byte[]) item;
            }
            int n = Math.min(len, current.length - offset);
            System.arraycopy(current, offset, b, off, n);
            offset += n;
            return n;
          }

          @Override
          public void close() {
            Body.this.close();
          }
        };

    void close() {
      if (closed) return;
      closed = true;
      queue.clear();
      queue.offer(new IOException("Media stream closed"));
      if (channel != null) {
        channel.close();
        cancellation.detach(channel);
      }
    }
  }

  private static long remainingMillis(long deadline) throws IOException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new IOException("Media opening timeout");
    return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
  }

  private static InputStream inflate(InputStream input) throws IOException {
    PushbackInputStream prefix = new PushbackInputStream(input, 2);
    byte[] signature = prefix.readNBytes(2);
    prefix.unread(signature);
    boolean zlib =
        signature.length == 2
            && (signature[0] & 15) == 8
            && (((signature[0] & 255) << 8) + (signature[1] & 255)) % 31 == 0;
    Inflater inflater = new Inflater(!zlib);
    return new InflaterInputStream(prefix, inflater) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          inflater.end();
        }
      }
    };
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI uri) {
    return uri.getPort() != -1
        ? uri.getPort()
        : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  private static void validateUri(URI uri, boolean publicOnly) throws IOException {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || !Set.of("http", "https")
            .contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
        || uri.getPort() == 0
        || uri.getPort() > 65535) throw new IOException("Unsafe media URL");
    if (publicOnly
        && (uri.getHost().equalsIgnoreCase("localhost")
            || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".localhost")))
      throw new IOException("Local media URL");
  }

  private static Map<String, String> validateHeaders(
      Map<String, String> headers, boolean publicOnly) throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    if (headers == null) return result;
    for (var entry : headers.entrySet()) {
      String n = entry.getKey(), v = entry.getValue();
      if (n == null
          || v == null
          || !n.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
          || v.length() > 4096
          || v.chars().anyMatch(c -> c < 32 && c != 9 || c == 127))
        throw new IOException("Invalid media header");
      n = n.toLowerCase(Locale.ROOT);
      if (RESERVED.contains(n)) throw new IOException("Reserved media header");
      if (publicOnly && !PUBLIC_HEADERS.contains(n)) continue;
      result.put(n, v);
    }
    return Map.copyOf(result);
  }

  @Override
  public void close() {
    dnsExecutor.shutdownNow();
    group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
  }
}
