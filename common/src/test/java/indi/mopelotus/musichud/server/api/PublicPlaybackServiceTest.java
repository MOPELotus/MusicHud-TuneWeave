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

    private static final class Harness implements AutoCloseable, IServerNetworkService {
        final Player owner = new Player(UUID.randomUUID(), "owner");
        final Player listener = new Player(UUID.randomUUID(), "listener");
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final BlockingQueue<PlaybackSession> sessions = new LinkedBlockingQueue<>();
        final BlockingQueue<PlaybackSession> listenerSessions = new LinkedBlockingQueue<>();
        final List<UUID> resolvers = new CopyOnWriteArrayList<>();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final MusicPlayerServerService service;
        volatile boolean failResolution;

        Harness() {
            service = new MusicPlayerServerService(forbidden(ServerConfig.class),
                    forbidden(IMusicApiService.class), this, tasks::add);
            ServerPlayerRegistry.getInstance().join(owner);
            ServerPlayerRegistry.getInstance().join(listener);
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
