package indi.mopelotus.musichud.client.audio.decoder.transport;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** Forces producer publication inside a reader's empty-poll window without timing retries. */
final class BodyTerminationChecks {
  static int checks;

  static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Gate timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  static class GateQueue extends ArrayBlockingQueue<Object> {
    final CountDownLatch emptyPoll = new CountDownLatch(1), releasePoll = new CountDownLatch(1);
    boolean first = true, armed = true;

    GateQueue() {
      super(128);
    }

    @Override
    public Object poll() {
      Object snapshot = super.poll();
      if (snapshot == null && first && armed) {
        first = false;
        emptyPoll.countDown();
        await(releasePoll);
      }
      return snapshot;
    }
  }

  static final class TerminalGateQueue extends ArrayBlockingQueue<Object> {
    final CountDownLatch terminalOffer = new CountDownLatch(1),
        releaseOffer = new CountDownLatch(1);
    final CompletableFuture<String> progress = new CompletableFuture<>();

    TerminalGateQueue() {
      super(128);
    }

    @Override
    public boolean offer(Object value) {
      if (!(value instanceof byte[]) && !(value instanceof IOException)) {
        terminalOffer.countDown();
        await(releaseOffer);
      }
      return super.offer(value);
    }

    @Override
    public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
      progress.complete("waiting-for-terminal-publication");
      return super.poll(timeout, unit);
    }
  }

  static final class Fixture implements AutoCloseable {
    final MediaTransport.Cancellation cancellation = new MediaTransport.Cancellation();
    final EmbeddedChannel channel;
    final InputStream input;

    Fixture(BlockingQueue<Object> queue) throws Exception {
      this(queue, -1);
    }

    Fixture(BlockingQueue<Object> queue, long expectedLength) throws Exception {
      Class<?> type = Class.forName(MediaTransport.class.getName() + "$Body");
      Constructor<?> constructor = type.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object body =
          constructor.newInstance(
              cancellation, 2000, null, URI.create("http://media.test/"), false);
      field(type, "queue").set(body, queue);
      field(type, "opening").set(body, false);
      input = (InputStream) field(type, "input").get(body);
      channel = new EmbeddedChannel((ChannelInboundHandlerAdapter) body);
      field(type, "channel").set(body, channel);
      cancellation.attach(channel);
      var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      if (expectedLength >= 0) HttpUtil.setContentLength(response, expectedLength);
      channel.writeInbound(response);
    }

    @Override
    public void close() throws IOException {
      input.close();
      channel.finishAndReleaseAll();
    }
  }

  static Field field(Class<?> type, String name) throws ReflectiveOperationException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  record Result(byte[] bytes, IOException failure) {}

  static CompletableFuture<Result> read(Fixture fixture) {
    CompletableFuture<Result> result = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              try {
                byte[] buffer = new byte[8];
                int length;
                while ((length = fixture.input.read(buffer)) != -1) bytes.write(buffer, 0, length);
                result.complete(new Result(bytes.toByteArray(), null));
              } catch (IOException failure) {
                result.complete(new Result(bytes.toByteArray(), failure));
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            });
    return result;
  }

  static void publicationWindow(String mode) throws Exception {
    GateQueue queue = new GateQueue();
    try (Fixture fixture = new Fixture(queue, mode.equals("truncated-last") ? 4 : -1)) {
      var result = read(fixture);
      await(queue.emptyPoll);
      try {
        if (mode.equals("content-end")
            || mode.equals("content-failure")
            || mode.equals("truncated-last")) {
          var content = new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
          fixture.channel.writeInbound(content);
          check(content.refCnt() == 0, "ordinary content ownership");
        }
        if (mode.equals("last-bytes")
            || mode.equals("content-end")
            || mode.equals("truncated-last")) {
          var last =
              new DefaultLastHttpContent(
                  Unpooled.wrappedBuffer(
                      mode.equals("last-bytes") ? new byte[] {1, 2, 3} : new byte[0]));
          fixture.channel.writeInbound(last);
          if (mode.equals("last-bytes")) check(last.refCnt() == 0, "last content ownership");
        } else if (mode.endsWith("failure")) {
          fixture
              .channel
              .pipeline()
              .fireExceptionCaught(new IOException("expected-terminal-failure"));
        } else if (mode.equals("close")) fixture.input.close();
        else if (mode.equals("cancel")) fixture.cancellation.cancel();
      } finally {
        queue.releasePoll.countDown();
      }
      Result value = result.get(3, TimeUnit.SECONDS);
      if (mode.equals("content-end") || mode.equals("last-bytes")) {
        check(
            Arrays.equals(new byte[] {1, 2, 3}, value.bytes()),
            "EOF discarded queued body: " + mode);
        check(value.failure() == null, "normal completion failed");
        check(fixture.input.read() == -1 && fixture.input.read() == -1, "EOF must remain stable");
      } else {
        check(value.failure() != null, "EOF swallowed terminal failure: " + mode);
        if (mode.endsWith("failure") || mode.equals("truncated-last")) {
          check(
              value
                  .failure()
                  .getMessage()
                  .equals(
                      mode.equals("truncated-last")
                          ? "Truncated media body"
                          : "expected-terminal-failure"),
              "wrong failure");
          check(
              value.bytes().length
                  == (mode.equals("content-failure") || mode.equals("truncated-last") ? 3 : 0),
              "failure overtook body");
        }
      }
    }
  }

  static void endedReaderStillObservesCancelOrClose(boolean cancel) throws Exception {
    var errorOffered = new CountDownLatch(1);
    var releaseError = new CountDownLatch(1);
    GateQueue queue =
        new GateQueue() {
          @Override
          public boolean offer(Object item) {
            if (armed && item instanceof IOException) {
              errorOffered.countDown();
              await(releaseError);
            }
            return super.offer(item);
          }
        };
    queue.armed = false;
    try (Fixture fixture = new Fixture(queue)) {
      fixture.channel.pipeline().fireExceptionCaught(new IOException("initial-terminal-error"));
      try {
        fixture.input.read();
        throw new AssertionError("initial failure missing");
      } catch (IOException expected) {
        check(expected.getMessage().equals("initial-terminal-error"), "initial failure");
      }
      queue.armed = true;
      var reader = read(fixture);
      await(queue.emptyPoll);
      var closer = new CompletableFuture<Void>();
      try {
        if (cancel) fixture.cancellation.cancel();
        else {
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      fixture.input.close();
                      closer.complete(null);
                    } catch (Throwable failure) {
                      closer.completeExceptionally(failure);
                    }
                  });
          await(errorOffered);
        }
        queue.releasePoll.countDown();
        check(
            reader.get(3, TimeUnit.SECONDS).failure() != null,
            "terminal EOF swallowed concurrent cancellation/close");
      } finally {
        queue.releasePoll.countDown();
        releaseError.countDown();
      }
      if (!cancel) closer.get(3, TimeUnit.SECONDS);
    }
  }

  static void consumedEndRemainsEofBeforeProducerStoresEnded() throws Exception {
    var endEnqueued = new CountDownLatch(1);
    var releaseProducer = new CountDownLatch(1);
    var progress = new CompletableFuture<String>();
    var queue =
        new ArrayBlockingQueue<Object>(128) {
          @Override
          public boolean offer(Object item) {
            boolean offered = super.offer(item);
            if (!(item instanceof byte[]) && !(item instanceof IOException)) {
              endEnqueued.countDown();
              await(releaseProducer);
            }
            return offered;
          }

          @Override
          public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
            progress.complete("waited-after-consuming-end");
            return super.poll(timeout, unit);
          }
        };
    try (Fixture fixture = new Fixture(queue)) {
      var producer = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  fixture.channel.writeInbound(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
                  producer.complete(null);
                } catch (Throwable failure) {
                  producer.completeExceptionally(failure);
                }
              });
      await(endEnqueued);
      var reader = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  check(
                      fixture.input.read() == -1 && fixture.input.read() == -1,
                      "repeated terminal read");
                  progress.complete("stable-eof");
                  reader.complete(null);
                } catch (Throwable failure) {
                  reader.completeExceptionally(failure);
                  progress.complete("failed");
                }
              });
      try {
        check(
            progress.get(3, TimeUnit.SECONDS).equals("stable-eof"),
            "consuming END must establish reader-owned EOF before producer resumes");
      } finally {
        releaseProducer.countDown();
      }
      producer.get(3, TimeUnit.SECONDS);
      reader.get(3, TimeUnit.SECONDS);
    }
  }

  static void terminalMustBePublishedBeforeEnded() throws Exception {
    TerminalGateQueue queue = new TerminalGateQueue();
    try (Fixture fixture = new Fixture(queue)) {
      CompletableFuture<Void> producer = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  fixture.channel.writeInbound(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
                  producer.complete(null);
                } catch (Throwable failure) {
                  producer.completeExceptionally(failure);
                }
              });
      await(queue.terminalOffer);
      var reader = read(fixture);
      reader.whenComplete(
          (value, failure) -> queue.progress.complete("reader-finished-before-terminal"));
      try {
        check(
            queue.progress.get(3, TimeUnit.SECONDS).equals("waiting-for-terminal-publication"),
            "ended became visible before its terminal queue item");
      } finally {
        queue.releaseOffer.countDown();
      }
      producer.get(3, TimeUnit.SECONDS);
      Result value = reader.get(3, TimeUnit.SECONDS);
      check(value.bytes().length == 0 && value.failure() == null, "empty terminal completion");
    }
  }

  public static void main(String[] args) throws Exception {
    checks = 0;
    for (String mode :
        List.of(
            "content-end",
            "last-bytes",
            "content-failure",
            "failure",
            "truncated-last",
            "close",
            "cancel")) publicationWindow(mode);
    endedReaderStillObservesCancelOrClose(false);
    endedReaderStillObservesCancelOrClose(true);
    consumedEndRemainsEofBeforeProducerStoresEnded();
    terminalMustBePublishedBeforeEnded();
    System.out.println("Body termination checks passed: " + checks);
  }
}
