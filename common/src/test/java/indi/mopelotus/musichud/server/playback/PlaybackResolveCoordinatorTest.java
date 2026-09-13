package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.Fee;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.PlaybackResolution;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ResolvePlaybackResultMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackResolveCoordinatorTest {
    @Test
    void obsoleteSessionNeverDispatchesResolution() {
        var coordinator = new PlaybackResolveCoordinator((player, request) ->
                org.junit.jupiter.api.Assertions.fail("obsolete session dispatched"), Duration.ofSeconds(30));
        assertTrue(coordinator.resolveWith(new TestPlayer(new UUID(0, 5), "owner"),
                track(), 1, () -> false).isEmpty());
    }

    @Test
    void switchCancelsOldRefreshWithoutCancellingNewSessionRequest() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicInteger(1);
        var requests = new java.util.concurrent.LinkedBlockingQueue<
                indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ResolvePlaybackRequestMessage>();
        var coordinator = new PlaybackResolveCoordinator((player, request) -> requests.add(request),
                Duration.ofSeconds(30));
        TestPlayer player = new TestPlayer(new UUID(0, 5), "owner");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> coordinator.resolveWith(player, track(), 1, () -> active.get() == 1));
            var oldRequest = requests.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(oldRequest);
            active.set(2);
            var current = executor.submit(() -> coordinator.resolveWith(player, track(), 0, () -> active.get() == 2));
            var currentRequest = requests.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(currentRequest);
            coordinator.cancelStaleRequests();
            assertTrue(old.get(2, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
            var resolution = new PlaybackResolution(track(), resource(track()));
            coordinator.accept(player, ResolvePlaybackResultMessage.success(oldRequest.requestId(), 1, resolution));
            org.junit.jupiter.api.Assertions.assertFalse(current.isDone());
            coordinator.accept(player, ResolvePlaybackResultMessage.success(currentRequest.requestId(), 0, resolution));
            assertTrue(current.get(2, java.util.concurrent.TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void resultAcceptedJustBeforeStopIsDiscardedByWaitingCaller() {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var holder = new AtomicReference<PlaybackResolveCoordinator>();
        TestPlayer player = new TestPlayer(new UUID(0, 5), "owner");
        var coordinator = new PlaybackResolveCoordinator((resolver, request) -> {
            holder.get().accept(resolver, ResolvePlaybackResultMessage.success(request.requestId(), 1,
                    new PlaybackResolution(track(), resource(track()))));
            active.set(false);
        }, Duration.ofSeconds(30));
        holder.set(coordinator);
        assertTrue(coordinator.resolveWith(player, track(), 1, active::get).isEmpty());
    }

    @Test
    void disconnectedResolverWakesPendingRequestAndLateResultCannotCompleteReconnect() throws Exception {
        var online = new java.util.concurrent.atomic.AtomicBoolean(true);
        var requests = new java.util.concurrent.LinkedBlockingQueue<
                indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ResolvePlaybackRequestMessage>();
        TestPlayer player = new TestPlayer(new UUID(0, 5), "owner");
        var coordinator = new PlaybackResolveCoordinator((ignored, request) -> requests.add(request),
                Duration.ofSeconds(30), ignored -> online.get());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.resolveWith(player, track(), 0));
            var oldRequest = requests.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(oldRequest);
            online.set(false);
            coordinator.cancelUnavailableResolvers();
            assertTrue(first.get(2, java.util.concurrent.TimeUnit.SECONDS).isEmpty());

            online.set(true);
            var second = executor.submit(() -> coordinator.resolveWith(player, track(), 0));
            var newRequest = requests.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(newRequest);
            var resolution = new PlaybackResolution(track(), resource(track()));
            coordinator.accept(player, ResolvePlaybackResultMessage.success(
                    oldRequest.requestId(), 0, resolution));
            org.junit.jupiter.api.Assertions.assertFalse(second.isDone());
            coordinator.accept(player, ResolvePlaybackResultMessage.success(
                    newRequest.requestId(), 0, resolution));
            assertTrue(second.get(2, java.util.concurrent.TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void alreadyOfflineResolverIsNotSentARequest() {
        var coordinator = new PlaybackResolveCoordinator((player, request) ->
                org.junit.jupiter.api.Assertions.fail("must not send to offline player"),
                Duration.ofSeconds(30), ignored -> false);
        assertTrue(coordinator.resolveWith(new TestPlayer(new UUID(0, 5), "offline"), track(), 0).isEmpty());
    }

    @Test
    void acceptsOnlyTheRequestedResolverAndRevision() {
        TestPlayer expected = new TestPlayer(new UUID(0, 2), "expected");
        TestPlayer forged = new TestPlayer(new UUID(0, 3), "forged");
        MusicDetail track = track();
        PlaybackResolution resolution = new PlaybackResolution(track, resource(track));
        AtomicReference<PlaybackResolveCoordinator> holder = new AtomicReference<>();
        PlaybackResolveCoordinator coordinator = new PlaybackResolveCoordinator((resolver, request) -> {
            ResolvePlaybackResultMessage result = ResolvePlaybackResultMessage.success(
                    request.requestId(), request.revision(), resolution);
            holder.get().accept(forged, result);
            holder.get().accept(expected, new ResolvePlaybackResultMessage(
                    result.requestId(), result.revision() + 1, true,
                    result.musicDetail(), result.resourceInfo(), ""));
            holder.get().accept(expected, result);
        }, Duration.ofSeconds(1));
        holder.set(coordinator);

        Optional<PlaybackResolution> resolved = coordinator.resolveWith(expected, track, 4);

        assertTrue(resolved.isPresent());
        assertEquals(track.getSourceRef(), resolved.orElseThrow().musicDetail().getSourceRef());
    }

    @Test
    void prioritizesOwnerThenUsesStableUuidOrder() {
        TestPlayer later = new TestPlayer(new UUID(0, 9), "later");
        TestPlayer owner = new TestPlayer(new UUID(0, 5), "owner");
        TestPlayer earlier = new TestPlayer(new UUID(0, 1), "earlier");

        List<IPlayerClient> ordered = PlaybackResolveCoordinator.prioritizeResolvers(
                List.of(later, owner, earlier), owner.getUUID());

        assertEquals(List.of(owner, earlier, later), ordered);
    }

    @Test
    void unavailableOwnerFailsWithoutInventingServerCredentials() {
        TestPlayer owner = new TestPlayer(new UUID(0, 5), "owner");
        AtomicReference<PlaybackResolveCoordinator> holder = new AtomicReference<>();
        PlaybackResolveCoordinator coordinator = new PlaybackResolveCoordinator((resolver, request) ->
                holder.get().accept(owner, ResolvePlaybackResultMessage.failure(
                        request.requestId(), request.revision(), "resolve_failed")),
                Duration.ofSeconds(1));
        holder.set(coordinator);

        assertTrue(coordinator.resolveWith(owner, track(), 0).isEmpty());
    }

    @Test
    void ownerScopedCloudTrackNeverFallsBackToAnotherAccount() {
        TestPlayer owner = new TestPlayer(new UUID(0, 5), "owner");
        TestPlayer listener = new TestPlayer(new UUID(0, 7), "listener");
        MusicDetail cloudTrack = track();
        cloudTrack.setPusherInfo(new PusherInfo(owner.getUUID(), owner.getName()));
        cloudTrack.setCloudSource(true);

        assertEquals(List.of(owner), PlaybackResolveCoordinator.eligibleResolvers(
                List.of(listener, owner), cloudTrack));
        assertTrue(PlaybackResolveCoordinator.eligibleResolvers(
                List.of(listener), cloudTrack).isEmpty());
    }

    private static MusicDetail track() {
        return MusicDetail.fromTuneWeave(1, "netease:track:1", "track", "Track",
                60_000, Album.NONE, List.<Artist>of());
    }

    private static MusicResourceInfo resource(MusicDetail track) {
        return new MusicResourceInfo(track.getId(), "https://8.8.8.8/audio", 0, 0,
                FormatType.AUTO, "", Fee.UNSET, track.getDurationMillis());
    }

    private record TestPlayer(UUID uuid, String name) implements IPlayerClient {
        @Override
        public UUID getUUID() {
            return uuid;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ClientType getClientType() {
            return ClientType.REMOTE;
        }
    }
}
