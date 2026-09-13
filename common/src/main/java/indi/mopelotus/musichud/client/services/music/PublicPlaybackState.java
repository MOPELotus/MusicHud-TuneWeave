package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.IdlePreview;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Accepts server state before starting local audio; local completion never publishes public state. */
final class PublicPlaybackState {
    interface Output {
        void publish(PlaybackSession session, MusicDetail next);
        default void preview(MusicDetail next) {}
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
    private long previewRevision;
    private IdlePreview preview;
    private IdlePreview pendingPreview;

    PublicPlaybackState(Object lock, Output output, Executor notifications) {
        this.lock = Objects.requireNonNull(lock);
        this.output = Objects.requireNonNull(output);
        this.notifications = Objects.requireNonNull(notifications);
    }

    boolean accept(PlaybackSession update, MusicDetail next) {
        return accept(update, next, 0);
    }

    boolean accept(PlaybackSession update, MusicDetail next, long revision) {
        Objects.requireNonNull(update);
        if (revision < 0) throw new IllegalArgumentException("Negative preview revision");
        synchronized (lock) {
            if (!update.supersedes(session)) {
                if (update.sequence() == session.sequence() && update.sessionId().equals(session.sessionId()))
                    updatePreview(new IdlePreview(update.sessionId(), update.sequence(), revision, next));
                return false;
            }
            IdlePreview selected = new IdlePreview(update.sessionId(), update.sequence(), revision, next);
            if (preview != null && preview.matches(update) && preview.revision() > selected.revision()) selected = preview;
            if (pendingPreview != null) {
                if (pendingPreview.matches(update) && pendingPreview.revision() > selected.revision()) selected = pendingPreview;
                if (pendingPreview.sequence() <= update.sequence()) pendingPreview = null;
            }
            session = update;
            preview = selected;
            previewRevision = selected.revision();
            long ticket = ++generation;
            output.publish(update, update.isActive() ? selected.music() : MusicDetail.NONE);
            // Snapshot listeners can synchronously disconnect or accept a newer update.
            if (ticket != generation) return true;
            if (!update.isActive()) output.stop();
            else start(update, ticket, false);
            return true;
        }
    }

    boolean updatePreview(IdlePreview update) {
        Objects.requireNonNull(update);
        synchronized (lock) {
            if (update.sequence() > session.sequence()) {
                // Network handlers may execute before the corresponding initial/switch snapshot.
                // Keep only one future update, bounded independently of incoming traffic.
                if (pendingPreview == null || update.sequence() > pendingPreview.sequence()
                        || (update.sequence() == pendingPreview.sequence() && update.sessionId().equals(pendingPreview.sessionId()) && update.revision() > pendingPreview.revision())) {
                    pendingPreview = update;
                }
                return false;
            }
            if (!session.isActive() || !update.matches(session) || update.revision() <= previewRevision) return false;
            preview = update;
            previewRevision = update.revision();
            output.preview(update.music());
            return true;
        }
    }

    IdlePreview preview() {
        synchronized (lock) {
            return preview == null ? new IdlePreview(session.sessionId(), session.sequence(), previewRevision, MusicDetail.NONE) : preview;
        }
    }

    void reset() {
        synchronized (lock) {
            session = PlaybackSession.NONE;
            preview = null;
            pendingPreview = null;
            previewRevision = 0;
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
