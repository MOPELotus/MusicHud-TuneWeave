package indi.mopelotus.musichud.client.services.music;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One queue action belongs to the connection and account where it was requested; never replay it. */
public final class QueueSubmissionGate {
    public enum Result { SUBMITTED, REJECTED }
    public record Scope(Object connection, Object player, int generation, Object account) {
        boolean same(Scope other) {
            return connection != null && player != null && other != null
                    && connection == other.connection && player == other.player
                    && generation == other.generation && account == other.account;
        }
    }
    public record Submission(CompletableFuture<Result> result, BooleanSupplier current) {}
    private final Object owner;
    private final Executor client;
    private final Supplier<Scope> scope;
    private final BooleanSupplier ready;

    public QueueSubmissionGate(Object owner, Executor client, Supplier<Scope> scope, BooleanSupplier ready) {
        this.owner = owner; this.client = client; this.scope = scope; this.ready = ready;
    }

    public Submission submit(Runnable send) {
        Scope expected = scope.get();
        BooleanSupplier current = () -> expected.same(scope.get());
        var result = new CompletableFuture<Result>();
        try {
            client.execute(() -> {
                try {
                    Result outcome;
                    synchronized (owner) {
                        if (!current.getAsBoolean() || !ready.getAsBoolean()) outcome = Result.REJECTED;
                        else {
                            send.run();
                            outcome = Result.SUBMITTED;
                        }
                    }
                    // Never run future callbacks under the connection owner's lock.
                    // Submitted to the transport, not acknowledged by the server queue.
                    result.complete(outcome);
                } catch (RuntimeException error) { result.completeExceptionally(error); }
            });
        } catch (RuntimeException error) { result.completeExceptionally(error); }
        return new Submission(result, current);
    }
}
