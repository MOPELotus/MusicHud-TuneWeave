package indi.mopelotus.musichud.client.audio;

import java.time.ZonedDateTime;
import java.util.concurrent.*;
import java.util.function.*;

/** Current/pending/retiring ownership with one bounded crossfade. No OpenAL dependency. */
final class PlaybackHandoff<L extends PlaybackHandoff.Lane> {
    interface Lane { void gain(float gain); void discard(); }
    private final Executor startExecutor;
    private final Consumer<Runnable> scheduleFrame;
    private final LongSupplier nanoTime;
    private long generation;
    private volatile L current;
    private volatile L pending;
    private L retiring;
    private CompletableFuture<ZonedDateTime> pendingResult;

    PlaybackHandoff(Executor startExecutor, Consumer<Runnable> scheduleFrame, LongSupplier nanoTime) {
        this.startExecutor = startExecutor; this.scheduleFrame = scheduleFrame; this.nanoTime = nanoTime;
    }

    L current() { return current; }
    L pending() { return pending; }
    boolean preparing() { return pending != null; }

    CompletableFuture<ZonedDateTime> begin(L candidate, Function<L, CompletableFuture<ZonedDateTime>> start) {
        CompletableFuture<ZonedDateTime> result = new CompletableFuture<>();
        long ticket;
        synchronized (this) {
            ticket = ++generation;
            cancelPending();
            if (retiring != null) { retiring.discard(); retiring = null; }
            if (current != null) current.gain(1);
            pending = candidate; pendingResult = result;
            candidate.gain(candidate == current ? 1 : 0);
        }
        CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                if (generation != ticket || pending != candidate) throw new CancellationException("Playback preparation was superseded");
            }
            return start.apply(candidate);
        }, startExecutor).thenCompose(Function.identity()).orTimeout(30, TimeUnit.SECONDS).whenCompleteAsync((started, error) -> {
            long transitionStart = 0;
            boolean fade = false;
            synchronized (this) {
                if (generation != ticket || pending != candidate) return;
                pending = null;
                if (error != null) {
                    if (candidate != current) candidate.discard();
                } else {
                    L old = current;
                    current = candidate;
                    retiring = old == candidate ? null : old;
                    fade = old != candidate;
                    transitionStart = nanoTime.getAsLong();
                    if (!fade) candidate.gain(1);
                }
            }
            if (error != null) result.completeExceptionally(error);
            else {
                if (fade) fade(ticket, transitionStart);
                result.complete(started);
            }
            synchronized (this) {
                if (pendingResult == result && pending == null) pendingResult = null;
            }
        }, startExecutor);
        return result;
    }

    private void fade(long ticket, long started) {
        synchronized (this) {
            if (generation != ticket || current == null) return;
            float fraction = (float) Math.clamp((nanoTime.getAsLong() - started) / 150_000_000.0, 0, 1);
            current.gain(fraction);
            if (retiring != null) retiring.gain(1 - fraction);
            if (fraction >= 1) {
                if (retiring != null) retiring.discard();
                retiring = null;
                return;
            }
        }
        scheduleFrame.accept(() -> fade(ticket, started));
    }

    synchronized void stop() {
        generation++;
        cancelPending();
        if (current != null) current.discard();
        if (retiring != null && retiring != current) retiring.discard();
        current = null; retiring = null;
    }

    private void cancelPending() {
        L previous = pending;
        var previousResult = pendingResult;
        pending = null; pendingResult = null;
        if (previous != null && previous != current) previous.discard();
        if (previousResult != null) previousResult.completeExceptionally(new CancellationException("Playback preparation was superseded"));
    }
}
