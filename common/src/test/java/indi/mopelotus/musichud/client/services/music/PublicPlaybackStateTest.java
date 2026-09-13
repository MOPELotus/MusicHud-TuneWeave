package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.beans.music.*;
import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PublicPlaybackStateTest {
    @Test void logoutRecoveryKeepsPublicStateAndRejectsPreRecoveryFailure() {
        Fixture f = new Fixture();
        PlaybackSession session = session(1);
        MusicDetail next = session(2).musicDetail();
        f.state.accept(session, next);
        f.starts.getFirst().completeExceptionally(new IllegalStateException("old decoder"));
        f.state.recoverLocalPlayback();
        f.drain();
        assertSame(session, f.published);
        assertSame(next, f.next);
        assertEquals(List.of("publish:1", "play:1", "restart:1", "play:1"), f.events);
        assertTrue(f.failures.isEmpty());
        f.starts.getLast().complete(null);
        assertSame(session, f.published);
    }

    @Test void repeatedRecoveryAndDisconnectRetireAllEarlierCallbacks() {
        Fixture f = new Fixture();
        f.state.accept(session(1), MusicDetail.NONE);
        f.state.recoverLocalPlayback();
        f.state.recoverLocalPlayback();
        f.state.reset();
        for (var start : f.starts) start.completeExceptionally(new IllegalStateException("retired"));
        f.drain();
        assertTrue(f.failures.isEmpty());
        assertSame(PlaybackSession.NONE, f.published);
        int starts = f.starts.size();
        f.state.recoverLocalPlayback();
        assertEquals(starts, f.starts.size());
        assertEquals(2, f.stops);
    }

    @Test void recoveryFailureKeepsSessionAndNextServerSwitchCanPlay() {
        Fixture f = new Fixture();
        PlaybackSession original = session(1);
        f.state.accept(original, MusicDetail.NONE);
        f.state.recoverLocalPlayback();
        f.starts.getLast().completeExceptionally(new IllegalStateException("resource unavailable"));
        f.drain();
        assertSame(original, f.published);
        assertEquals(List.of(original), f.failures);
        PlaybackSession next = session(2);
        assertTrue(f.state.accept(next, MusicDetail.NONE));
        f.starts.getLast().complete(null);
        assertSame(next, f.published);
    }

    @Test void bufferingAndFailureKeepNewAuthoritativeSongLyricsAndTimeline() {
        Fixture f = new Fixture();
        PlaybackSession first = session(1), second = session(2);
        f.state.accept(first, MusicDetail.NONE);
        f.starts.getFirst().complete(null);
        f.state.accept(second, first.musicDetail());
        assertSame(second, f.published);
        assertSame(first.musicDetail(), f.next);
        assertEquals(List.of("publish:1", "play:1", "publish:2", "play:2"), f.events);
        f.starts.getLast().completeExceptionally(new IllegalStateException("decoder failed"));
        f.drain();
        assertSame(second, f.published);
        assertEquals(List.of(second), f.failures);
        assertEquals(second.startTime(), f.published.startTime());
    }

    @Test void lateStartSuccessOrQueuedFailureCannotPublishAcrossSwitchStopOrReset() {
        for (int action = 0; action < 3; action++) {
            Fixture f = new Fixture();
            PlaybackSession old = session(1);
            f.state.accept(old, MusicDetail.NONE);
            CompletableFuture<Void> oldStart = f.starts.getFirst();
            oldStart.completeExceptionally(new IllegalStateException("old failed"));
            if (action == 0) f.state.accept(session(2), MusicDetail.NONE);
            else if (action == 1) f.state.accept(PlaybackSession.stopped(2), MusicDetail.NONE);
            else f.state.reset();
            PlaybackSession current = f.published;
            f.drain();
            assertSame(current, f.published);
            assertTrue(f.failures.isEmpty());
        }
        Fixture f = new Fixture();
        f.state.accept(session(1), MusicDetail.NONE);
        var oldStart = f.starts.getFirst();
        f.state.accept(session(2), MusicDetail.NONE);
        PlaybackSession current = f.published;
        oldStart.complete(null);
        f.drain();
        assertSame(current, f.published);
    }

    @Test void refreshPreservesSessionTimelineAndRejectsDuplicateOrOlderRevision() {
        Fixture f = new Fixture();
        PlaybackSession original = session(1);
        PlaybackSession refresh = new PlaybackSession(original.sessionId(), 1, 1,
                original.musicDetail(), original.resourceInfo(), original.startTime());
        assertTrue(f.state.accept(original, MusicDetail.NONE));
        assertTrue(f.state.accept(refresh, MusicDetail.NONE));
        assertFalse(f.state.accept(original, MusicDetail.NONE));
        assertFalse(f.state.accept(refresh, MusicDetail.NONE));
        assertSame(refresh, f.published);
        assertEquals(2, f.starts.size());
    }

    @Test void synchronousAudioFailureDoesNotUndoPublicState() {
        Fixture f = new Fixture(); f.throwOnStart = true;
        PlaybackSession update = session(1);
        assertTrue(f.state.accept(update, MusicDetail.NONE));
        f.drain();
        assertSame(update, f.published);
        assertEquals(List.of(update), f.failures);
    }

    @Test void snapshotListenerDisconnectPreventsOldAudioFromStarting() {
        Fixture f = new Fixture();
        f.onPublish = () -> { f.onPublish = () -> {}; f.state.reset(); };
        f.state.accept(session(1), MusicDetail.NONE);
        assertSame(PlaybackSession.NONE, f.published);
        assertTrue(f.starts.isEmpty());
        assertEquals(1, f.stops);
    }

    private static PlaybackSession session(long sequence) {
        MusicDetail song = MusicDetail.fromTuneWeave(sequence, "netease:track:" + sequence,
                "track", "Song " + sequence, 180_000, Album.NONE, List.of());
        return new PlaybackSession(UUID.randomUUID(), sequence, 0, song, MusicResourceInfo.NONE,
                ZonedDateTime.parse("2026-09-12T12:00:00Z"));
    }

    private static final class Fixture implements PublicPlaybackState.Output {
        final List<String> events = new ArrayList<>();
        final List<CompletableFuture<Void>> starts = new ArrayList<>();
        final List<PlaybackSession> failures = new ArrayList<>();
        final Queue<Runnable> notifications = new ArrayDeque<>();
        final PublicPlaybackState state = new PublicPlaybackState(this, this, notifications::add);
        PlaybackSession published;
        MusicDetail next;
        boolean throwOnStart;
        int stops;
        Runnable onPublish = () -> {};
        public void publish(PlaybackSession session, MusicDetail next) {
            published = session; this.next = next; events.add("publish:" + session.sequence()); onPublish.run();
        }
        public CompletableFuture<?> play(PlaybackSession session) {
            assertSame(session, published, "public state must precede local start");
            events.add("play:" + session.sequence());
            if (throwOnStart) throw new IllegalStateException("synchronous local failure");
            var future = new CompletableFuture<Void>(); starts.add(future); return future;
        }
        public void stop() { stops++; }
        public CompletableFuture<?> restart(PlaybackSession session) {
            events.add("restart:" + session.sequence());
            return play(session);
        }
        public void failed(PlaybackSession session) { failures.add(session); }
        void drain() { while (!notifications.isEmpty()) notifications.remove().run(); }
    }
}
