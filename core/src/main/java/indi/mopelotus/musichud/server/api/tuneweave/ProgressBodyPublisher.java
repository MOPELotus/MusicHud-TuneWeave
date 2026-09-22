package indi.mopelotus.musichud.server.api.tuneweave;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Reports bytes consumed by the HTTP client without loading the upload into memory. */
final class ProgressBodyPublisher implements HttpRequest.BodyPublisher {
    private final HttpRequest.BodyPublisher delegate;
    private final LongConsumer progress;
    private final BooleanSupplier cancelled;

    ProgressBodyPublisher(HttpRequest.BodyPublisher delegate, LongConsumer progress, BooleanSupplier cancelled) {
        this.delegate = delegate;
        this.progress = progress;
        this.cancelled = cancelled;
    }

    @Override public long contentLength() { return delegate.contentLength(); }

    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
        delegate.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            private long sent;
            private boolean done;

            @Override public void onSubscribe(Flow.Subscription upstream) {
                subscription = upstream;
                downstream.onSubscribe(upstream);
            }

            @Override public void onNext(ByteBuffer bytes) {
                if (done) return;
                if (cancelled.getAsBoolean()) {
                    subscription.cancel();
                    onError(new CancellationException("Cloud upload cancelled"));
                    return;
                }
                int count = bytes.remaining();
                downstream.onNext(bytes);
                sent += count;
                progress.accept(sent);
            }

            @Override public void onError(Throwable error) {
                if (done) return;
                done = true;
                downstream.onError(error);
            }

            @Override public void onComplete() {
                if (done) return;
                done = true;
                downstream.onComplete();
            }
        });
    }
}
