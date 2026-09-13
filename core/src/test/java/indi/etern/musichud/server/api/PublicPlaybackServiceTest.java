package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.IServerNetworkService;
import indi.mopelotus.musichud.network.payloads.S2CPayload;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.PlaybackResourceFailureMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ResolvePlaybackResultMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ResolvePlaybackRequestMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SwitchMusicMessage;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.RotateNextToPlayRequest;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.UpdateNextToPlayMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PublicPlaybackServiceTest {
    @Test void clientIdleSourceStartsPublicPlaybackWithoutServerProviderLookup() throws Exception {
        try (Harness h = new Harness()) {
            Playlist playlist = Playlist.fromTuneWeave(42, "netease:playlist:42", "Source", "", 1, 0,
                    indi.mopelotus.musichud.beans.user.Profile.ANONYMOUS);
            playlist.getTracks().add(MusicDetail.fromTuneWeave(1, "netease:track:1", "track", "Track", 60_000, Album.NONE, List.of()));
            h.service.addIdlePlaySource(playlist, new PusherInfo(h.owner.uuid(), h.owner.name()));
            Runnable pusher = h.tasks.poll(2, TimeUnit.SECONDS);
            assertNotNull(pusher);
            h.workers.submit(pusher);
            PlaybackSession session = h.sessions.poll(2, TimeUnit.SECONDS);
            assertNotNull(session);
            assertEquals("netease:track:1", session.musicDetail().getSourceRef());
            assertEquals(h.owner.uuid(), session.musicDetail().getPusherInfo().getPlayerUUID());
        }
    }

    @Test
    void ownerResolvedVipResourceIsBroadcastToListenerAndAvailableInInitialState() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession session = h.start();
            assertSame(session, h.listenerSessions.poll(2, TimeUnit.SECONDS));
            assertEquals(Fee.VIP, session.resourceInfo().getFee());
            assertSame(session, h.service.buildInitialStateFor(h.listener).getPlaybackSession());
            assertEquals(List.of(h.owner.uuid()), h.resolvers,
                    "listener receives shared resource without resolving it again");
        }
    }

    @Test
    void cloudRefreshDoesNotFallBackToListenerAfterOwnerLeaves() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession session = h.start(true);
            assertSame(session, h.listenerSessions.poll(2, TimeUnit.SECONDS));
            ServerPlayerRegistry.getInstance().leave(h.owner);
            h.service.reportPlaybackResourceFailure(h.listener,
                    new PlaybackResourceFailureMessage(session.sessionId(), session.revision()));
            h.runNext();
            assertEquals(List.of(h.owner.uuid()), h.resolvers);
            assertSame(session, h.service.getCurrentPlaybackSession());
        }
    }

    @Test
    void oneListenerFailurePreservesPublicStateAndDistinctReportsRefreshSameTimeline() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession first = h.start();
            var report = new PlaybackResourceFailureMessage(first.sessionId(), first.revision());
            h.service.reportPlaybackResourceFailure(h.listener, report);
            h.service.reportPlaybackResourceFailure(h.listener, report);
            h.service.reportPlaybackResourceFailure(new Player(UUID.randomUUID(), "outsider"), report);
            assertTrue(h.tasks.isEmpty(), "duplicate/outsider reports must not meet threshold");
            assertSame(first, h.service.getCurrentPlaybackSession());
            h.service.reportPlaybackResourceFailure(h.owner, report);
            h.service.reportPlaybackResourceFailure(h.owner, report);
            assertEquals(1, h.tasks.size(), "only one refresh per revision");
            h.runNext();
            PlaybackSession refreshed = h.service.getCurrentPlaybackSession();
            assertEquals(first.sessionId(), refreshed.sessionId());
            assertEquals(first.sequence(), refreshed.sequence());
            assertEquals(first.startTime(), refreshed.startTime());
            assertEquals(first.revision() + 1, refreshed.revision());
            assertNotEquals(first.resourceInfo().getUrl(), refreshed.resourceInfo().getUrl());
            assertEquals(List.of(h.owner.uuid(), h.owner.uuid()), h.resolvers);
            h.service.reportPlaybackResourceFailure(h.owner, report);
            h.service.reportPlaybackResourceFailure(h.listener, report);
            assertTrue(h.tasks.isEmpty(), "old revision must not refresh the new resource");
        }
    }

    @Test
    void queuedRefreshAfterStopDoesNotResolveOrRevivePlayback() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession first = h.start();
            var report = new PlaybackResourceFailureMessage(first.sessionId(), first.revision());
            h.service.reportPlaybackResourceFailure(h.owner, report);
            h.service.reportPlaybackResourceFailure(h.listener, report);
            h.service.reset();
            PlaybackSession stopped = h.service.getCurrentPlaybackSession();
            assertFalse(stopped.isActive());
            h.runNext();
            assertSame(stopped, h.service.getCurrentPlaybackSession());
            assertEquals(1, h.resolvers.size(), "obsolete refresh must not contact any resolver");
        }
    }

    @Test
    void failedRefreshPreservesExistingPublicSession() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession first = h.start();
            h.failResolution = true;
            var report = new PlaybackResourceFailureMessage(first.sessionId(), first.revision());
            h.service.reportPlaybackResourceFailure(h.owner, report);
            h.service.reportPlaybackResourceFailure(h.listener, report);
            h.runNext();
            assertSame(first, h.service.getCurrentPlaybackSession());
            assertEquals(List.of(h.owner.uuid(), h.owner.uuid(), h.listener.uuid()), h.resolvers);
        }
    }

    @Test
    void lateJoinAndResyncPreserveCurrentTimelineWithoutNewResolution() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession started = h.start();
            assertSame(started, h.listenerSessions.poll(2, TimeUnit.SECONDS));
            var late = new Player(UUID.randomUUID(), "late joiner");
            ServerPlayerRegistry.getInstance().join(late);
            try {
                assertSame(started, h.service.buildInitialStateFor(late).getPlaybackSession());
                h.service.sendSyncPlayingStatusToPlayer(late);
                PlaybackSession firstSync = h.syncSessions.poll(2, TimeUnit.SECONDS);
                assertSame(started, firstSync);
                h.service.sendSyncPlayingStatusToPlayer(late);
                PlaybackSession repeatSync = h.syncSessions.poll(2, TimeUnit.SECONDS);
                assertSame(firstSync, repeatSync, "resync must not restart or replace the public timeline");
                assertEquals(List.of(h.owner.uuid()), h.resolvers,
                        "late join and resync must reuse the owner-resolved public resource");
                h.service.reset();
                PlaybackSession stopped = h.service.buildInitialStateFor(late).getPlaybackSession();
                assertFalse(stopped.isActive());
                assertTrue(stopped.sequence() > started.sequence());
                h.service.sendSyncPlayingStatusToPlayer(late);
                assertTrue(h.syncSessions.isEmpty(), "stopped playback must not be replayed to a late peer");
            } finally { ServerPlayerRegistry.getInstance().leave(late); }
        }
    }

    @Test void reconnectedPlayerRetiresOldPhysicalConnectionRerolls() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession playing = h.startIdle(3);
            var state = h.service.buildInitialStateFor(h.owner);
            var request = new RotateNextToPlayRequest(playing.sessionId(), playing.sequence(), state.getPreviewRevision());
            var replacement = new Player(h.owner.uuid(), "reconnected owner");
            ServerPlayerRegistry.getInstance().join(replacement);
            try {
                assertFalse(h.service.rotateNextToPlay(h.owner, request).extraData());
                assertTrue(h.service.rotateNextToPlay(replacement, request).extraData());
            } finally { ServerPlayerRegistry.getInstance().leave(replacement); }
        }
    }

    @Test void rerollIsOwnerOnlyAndDoesNotResolveOrReplaceCurrentSession() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession playing = h.startIdle(3);
            var before = h.service.buildInitialStateFor(h.owner);
            var request = new RotateNextToPlayRequest(playing.sessionId(), playing.sequence(), before.getPreviewRevision());
            assertFalse(h.service.rotateNextToPlay(h.listener, request).extraData());
            assertTrue(h.service.rotateNextToPlay(h.owner, request).extraData());
            var after = h.service.buildInitialStateFor(h.listener);
            assertSame(playing, after.getPlaybackSession());
            assertNotEquals(before.getNextIdle().getSourceRef(), after.getNextIdle().getSourceRef());
            assertEquals(before.getPreviewRevision() + 1, after.getPreviewRevision());
            assertEquals(1, h.resolvers.size());
            assertTrue(h.sessions.isEmpty(), "reroll must not publish an audio switch");
            assertEquals(2, h.previews.size(), "all listeners receive the preview");
            assertFalse(h.service.rotateNextToPlay(h.owner, request).extraData(), "repeated old request is stale");
        }
    }

    @Test void rerollRejectsQueueStopAndMissingAlternativeWithoutChangingPreview() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession playing = h.startIdle(1);
            var before = h.service.buildInitialStateFor(h.owner);
            var request = new RotateNextToPlayRequest(playing.sessionId(), playing.sequence(), before.getPreviewRevision());
            assertFalse(h.service.rotateNextToPlay(h.owner, request).extraData());
            assertEquals(before.getPreviewRevision(), h.service.buildInitialStateFor(h.owner).getPreviewRevision());
            h.service.pushMusicToQueue(playing.musicDetail(), new PusherInfo(h.owner.uuid(), h.owner.name()));
            assertFalse(h.service.rotateNextToPlay(h.owner, request).extraData());
            h.service.reset();
            assertFalse(h.service.rotateNextToPlay(h.owner, request).extraData());
            assertTrue(h.previews.isEmpty());
        }
    }

    @Test void rerollRemainsOrderedAcrossResourceRefresh() throws Exception {
        try (Harness h = new Harness()) {
            PlaybackSession playing = h.startIdle(3);
            var before = h.service.buildInitialStateFor(h.owner);
            var request = new RotateNextToPlayRequest(playing.sessionId(), playing.sequence(), before.getPreviewRevision());
            assertTrue(h.service.rotateNextToPlay(h.owner, request).extraData());
            var selected = h.service.buildInitialStateFor(h.owner);
            var report = new PlaybackResourceFailureMessage(playing.sessionId(), playing.revision());
            h.service.reportPlaybackResourceFailure(h.owner, report);
            h.service.reportPlaybackResourceFailure(h.listener, report);
            h.runNext();
            var refreshed = h.service.buildInitialStateFor(h.owner);
            assertEquals(playing.revision() + 1, refreshed.getPlaybackSession().revision());
            assertEquals(selected.getPreviewRevision(), refreshed.getPreviewRevision());
            assertEquals(selected.getNextIdle().getSourceRef(), refreshed.getNextIdle().getSourceRef());
        }
    }

    private static final class Harness implements AutoCloseable, IServerNetworkService {
        final Player owner = new Player(UUID.randomUUID(), "owner");
        final Player listener = new Player(UUID.randomUUID(), "listener");
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final BlockingQueue<PlaybackSession> sessions = new LinkedBlockingQueue<>();
        final BlockingQueue<PlaybackSession> syncSessions = new LinkedBlockingQueue<>();
        final BlockingQueue<PlaybackSession> listenerSessions = new LinkedBlockingQueue<>();
        final List<IdlePreview> previews = new CopyOnWriteArrayList<>();
        final List<UUID> resolvers = new CopyOnWriteArrayList<>();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final MusicPlayerServerService service;
        volatile boolean failResolution;

        Harness() {
            service = new MusicPlayerServerService(forbidden(ServerConfig.class),
                    this, tasks::add);
            ServerPlayerRegistry.getInstance().join(owner);
            ServerPlayerRegistry.getInstance().join(listener);
        }

        PlaybackSession startIdle(int count) throws Exception {
            Playlist playlist = Playlist.fromTuneWeave(42, "netease:playlist:42", "Source", "", count, 0,
                    indi.mopelotus.musichud.beans.user.Profile.ANONYMOUS);
            for (int i = 1; i <= count; i++) playlist.getTracks().add(MusicDetail.fromTuneWeave(i,
                    "netease:track:" + i, "track", "Track " + i, 60_000, Album.NONE, List.of()));
            service.addIdlePlaySource(playlist, new PusherInfo(owner.uuid(), owner.name()));
            Runnable pusher = tasks.poll(2, TimeUnit.SECONDS); assertNotNull(pusher);
            workers.submit(pusher);
            PlaybackSession session = sessions.poll(2, TimeUnit.SECONDS); assertNotNull(session);
            tasks.clear(); // Retire the unrelated source-list debounce in this fixture.
            return session;
        }

        PlaybackSession start() throws Exception {
            return start(false);
        }

        PlaybackSession start(boolean cloud) throws Exception {
            MusicDetail track = MusicDetail.fromTuneWeave(1, "netease:track:1", "track", "Track",
                    60_000, Album.NONE, List.of());
            track.setCloudSource(cloud);
            service.pushMusicToQueue(track, new PusherInfo(owner.uuid(), owner.name()));
            Runnable pusher = tasks.poll(2, TimeUnit.SECONDS);
            assertNotNull(pusher);
            workers.submit(pusher);
            PlaybackSession session = sessions.poll(2, TimeUnit.SECONDS);
            assertNotNull(session, "real queue/resolve/publish path must produce a session");
            return session;
        }

        void runNext() throws Exception {
            Runnable task = tasks.poll(2, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
        }

        @Override
        public <T extends S2CPayload> void sendToPlayer(IPlayerClient player, T payload) {
            if (payload instanceof ResolvePlaybackRequestMessage request) {
                resolvers.add(player.getUUID());
                if (failResolution) {
                    service.acceptPlaybackResolution(player, ResolvePlaybackResultMessage.failure(
                            request.requestId(), request.revision(), "unavailable"));
                } else {
                    MusicDetail track = request.requestedMusic();
                    var resource = new MusicResourceInfo(track.getId(),
                            "https://8.8.8.8/audio/" + request.revision(), 0, 0,
                            FormatType.AUTO, "", Fee.VIP, track.getDurationMillis());
                    service.acceptPlaybackResolution(player, ResolvePlaybackResultMessage.success(
                            request.requestId(), request.revision(), new PlaybackResolution(track, resource)));
                }
            } else if (payload instanceof indi.mopelotus.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage sync) {
                syncSessions.add(sync.playbackSession());
            } else if (payload instanceof UpdateNextToPlayMessage update) {
                previews.add(update.preview());
            } else if (payload instanceof SwitchMusicMessage update) {
                if (player.getUUID().equals(owner.uuid())) sessions.add(update.playbackSession());
                if (player.getUUID().equals(listener.uuid())) listenerSessions.add(update.playbackSession());
            }
        }

        @Override
        public void close() {
            service.reset();
            workers.shutdownNow();
            workers.close();
            ServerPlayerRegistry.getInstance().leave(owner);
            ServerPlayerRegistry.getInstance().leave(listener);
        }
    }

    private static <T> T forbidden(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("Unexpected dependency call: " + method); }));
    }

    private record Player(UUID uuid, String name) implements IPlayerClient {
        public UUID getUUID() { return uuid; }
        public String getName() { return name; }
        public ClientType getClientType() { return ClientType.REMOTE; }
    }
}
