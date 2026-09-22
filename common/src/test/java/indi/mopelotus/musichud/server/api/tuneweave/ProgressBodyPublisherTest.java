package indi.mopelotus.musichud.server.api.tuneweave;

import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressBodyPublisherTest {
    @Test void progressCountsOriginalBufferSizeEvenWhenHttpClientConsumesItsPosition() throws Exception {
        List<Long> counts = new CopyOnWriteArrayList<>();
        var publisher = new ProgressBodyPublisher(HttpRequest.BodyPublishers.ofByteArrays(
                List.of(new byte[2], new byte[3])), counts::add, () -> false);
        var reader = new Reader();
        publisher.subscribe(reader);
        reader.completion.get(5, TimeUnit.SECONDS);
        assertEquals(5, reader.bytes.get());
        assertEquals(5L, counts.getLast());
    }

    @Test void cancelledTransfersDeliverNoBodyAndTerminateWithCancellation() {
        var publisher = new ProgressBodyPublisher(HttpRequest.BodyPublishers.ofByteArray(new byte[50]),
                ignored -> fail("Cancelled transfer reported progress"), () -> true);
        var reader = new Reader();
        publisher.subscribe(reader);
        assertThrows(CancellationException.class, () -> reader.completion.get(5, TimeUnit.SECONDS));
        assertEquals(0, reader.bytes.get());
    }

    @Test void cancellationAfterOneChunkPreventsLaterChunks() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        var publisher = new ProgressBodyPublisher(HttpRequest.BodyPublishers.ofByteArrays(
                List.of(new byte[2], new byte[30])), ignored -> cancelled.set(true), cancelled::get);
        var reader = new Reader();
        publisher.subscribe(reader);
        assertThrows(CancellationException.class, () -> reader.completion.get(5, TimeUnit.SECONDS));
        assertEquals(2, reader.bytes.get());
    }

    @Test void eachHttpSubscriptionStartsCountingFromZeroAndPreservesContentLength() throws Exception {
        List<Long> counts = new CopyOnWriteArrayList<>();
        var publisher = new ProgressBodyPublisher(HttpRequest.BodyPublishers.ofByteArray(new byte[5]),
                counts::add, () -> false);
        assertEquals(5, publisher.contentLength());
        for (int i = 0; i < 2; i++) {
            var reader = new Reader();
            publisher.subscribe(reader);
            reader.completion.get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of(5L, 5L), counts);
    }

    private static final class Reader implements Flow.Subscriber<ByteBuffer> {
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final AtomicInteger bytes = new AtomicInteger();
        public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        public void onNext(ByteBuffer buffer) { bytes.addAndGet(buffer.remaining()); buffer.position(buffer.limit()); }
        public void onError(Throwable error) { completion.completeExceptionally(error); }
        public void onComplete() { completion.complete(null); }
    }
}
