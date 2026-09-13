package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Accepts server state before starting local audio; local completion never publishes public state. */
final class PublicPlaybackState {
    interface Output {
        void publish(PlaybackSession session, MusicDetail next);
        CompletableFuture<?> play(PlaybackSession session);
        CompletableFuture<?> restart(PlaybackSession session);
        void stop();
        void failed(PlaybackSession session);
    }

    private final Object lock;
    private final Output output;
    private final Executor notifications;
    private PlaybackSession session = PlaybackSession.NONE;
    private long generation;

    PublicPlaybackState(Object lock, Output output, Executor notifications) {
        this.lock = Objects.requireNonNull(lock);
        this.output = Objects.requireNonNull(output);
        this.notifications = Objects.requireNonNull(notifications);
    }

    boolean accept(PlaybackSession update, MusicDetail next) {
        Objects.requireNonNull(update);
        synchronized (lock) {
            if (!update.supersedes(session)) return false;
            session = update;
            long ticket = ++generation;
            output.publish(update, next);
            // Snapshot listeners can synchronously disconnect or accept a newer update.
            if (ticket != generation) return true;
            if (!update.isActive()) output.stop();
            else start(update, ticket, false);
            return true;
        }
    }

    void reset() {
        synchronized (lock) {
            session = PlaybackSession.NONE;
            long ticket = ++generation;
            output.publish(session, MusicDetail.NONE);
            if (ticket == generation) output.stop();
        }
    }

    void recoverLocalPlayback() {
        synchronized (lock) {
            long ticket = ++generation;
            if (session.isActive()) start(session, ticket, true);
            else output.stop();
        }
    }

    private void start(PlaybackSession update, long ticket, boolean recovering) {
        CompletableFuture<?> started;
        try { started = Objects.requireNonNull(recovering ? output.restart(update) : output.play(update)); }
        catch (RuntimeException error) { failed(update, ticket); return; }
        started.whenComplete((ignored, error) -> {
            if (error != null) failed(update, ticket);
        });
    }

    private void failed(PlaybackSession update, long ticket) {
        notifications.execute(() -> {
            synchronized (lock) {
                if (generation == ticket && session == update) output.failed(update);
            }
        });
    }
}
