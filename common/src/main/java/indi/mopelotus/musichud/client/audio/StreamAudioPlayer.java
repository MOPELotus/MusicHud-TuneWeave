package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public player facade: prepare a muted lane, then atomically hand over and fade for 150 ms. */
public final class StreamAudioPlayer {
    public enum Status { IDLE, BUFFERING, PLAYING, RETRYING, ERROR }
    private static final StreamAudioPlayer INSTANCE = new StreamAudioPlayer();
    private final Set<Consumer<Status>> listeners = ConcurrentHashMap.newKeySet();
    private final PlaybackStatusState status = new PlaybackStatusState(next -> listeners.forEach(listener -> listener.accept(next)));
    private final PlaybackHandoff<PlaybackEngine> handoff = new PlaybackHandoff<>(MusicHud.EXECUTOR,
            task -> CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS, MusicHud.EXECUTOR).execute(task), System::nanoTime);

    private StreamAudioPlayer() {}
    public static StreamAudioPlayer getInstance() { return INSTANCE; }
    public Status getStatus() { return status.get(); }
    public Set<Consumer<Status>> getStatusChangeListener() { return listeners; }

    private PlaybackEngine createEngine() {
        return observeEngine(new PlaybackEngine());
    }

    private PlaybackEngine observeEngine(PlaybackEngine engine) {
        engine.getStatusChangeListener().add(next -> MusicHud.EXECUTOR.execute(() -> {
            synchronized (this) {
                if (handoff.current() == engine && engine.getStatus() == next) status.observe(next);
            }
        }));
        return engine;
    }

    public synchronized CompletableFuture<ZonedDateTime> playSessionAsync(PlaybackSession session) {
        if (session == null || !session.isActive()) return CompletableFuture.failedFuture(new IllegalArgumentException("Inactive playback session"));
        PlaybackEngine current = handoff.current();
        PlaybackEngine pending = handoff.pending();
        PlaybackEngine candidate;
        if (current != null && current.session().sessionId().equals(session.sessionId())) candidate = current;
        else if (pending != null && pending.session().sessionId().equals(session.sessionId())) {
            // A server resource refresh can arrive before a recovery starter runs, too.
            candidate = observeEngine(pending.replaceForRecovery(session));
        } else candidate = createEngine();
        return startSession(session, candidate);
    }

    /** Account lifecycle recovery must not reuse a potentially wedged same-session engine. */
    public synchronized CompletableFuture<ZonedDateTime> restartSessionAsync(PlaybackSession session) {
        if (session == null || !session.isActive()) return CompletableFuture.failedFuture(new IllegalArgumentException("Inactive playback session"));
        status.invalidate();
        PlaybackEngine owner = handoff.pending();
        if (owner == null || !owner.session().sessionId().equals(session.sessionId())) owner = handoff.current();
        PlaybackEngine replacement = owner != null && owner.session().sessionId().equals(session.sessionId())
                ? observeEngine(owner.replaceForRecovery(session)) : createEngine();
        handoff.stop();
        return startSession(session, replacement);
    }

    private CompletableFuture<ZonedDateTime> startSession(PlaybackSession session, PlaybackEngine candidate) {
        long request = status.begin();
        return handoff.begin(candidate, engine -> engine.playSessionAsync(session)).whenComplete((started, error) -> {
            synchronized (this) {
                status.complete(request, candidate.getStatus(), error);
            }
        });
    }

    public synchronized CompletableFuture<ZonedDateTime> playDirectAsync(String identifier, FormatType format, ZonedDateTime startTime) {
        var candidate = createEngine();
        long request = status.begin();
        return handoff.begin(candidate, engine -> engine.playDirectAsync(identifier, format, startTime)).whenComplete((started, error) -> {
            synchronized (this) {
                status.complete(request, candidate.getStatus(), error);
            }
        });
    }

    public synchronized void stop() {
        status.invalidate();
        handoff.stop();
        status.observe(Status.IDLE);
    }
}
