package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackResolution;
import indi.mopelotus.musichud.network.IPlayerClient;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.ResolvePlaybackResultMessage;
import indi.mopelotus.musichud.network.payloads.pushMessages.s2c.ResolvePlaybackRequestMessage;

import java.time.Duration;
import java.util.Optional;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public final class PlaybackResolveCoordinator {
    private final ConcurrentHashMap<UUID, PendingResolve> pending = new ConcurrentHashMap<>();
    private final ResolveTransport transport;
    private final Duration timeout;
    private final java.util.function.Predicate<UUID> isOnline;

    public PlaybackResolveCoordinator(ResolveTransport transport, Duration timeout) {
        this(transport, timeout, ignored -> true);
    }

    public PlaybackResolveCoordinator(ResolveTransport transport, Duration timeout,
                                      java.util.function.Predicate<UUID> isOnline) {
        this.transport = transport;
        this.timeout = timeout;
        this.isOnline = isOnline;
    }

    public Optional<PlaybackResolution> resolveWith(IPlayerClient resolver,
                                                    MusicDetail requestedMusic,
                                                    int revision) {
        return resolveWith(resolver, requestedMusic, revision, () -> true);
    }

    public Optional<PlaybackResolution> resolveWith(IPlayerClient resolver,
                                                    MusicDetail requestedMusic, int revision,
                                                    BooleanSupplier stillCurrent) {
        UUID requestId = UUID.randomUUID();
        CompletableFuture<ResolvePlaybackResultMessage> future = new CompletableFuture<>();
        pending.put(requestId, new PendingResolve(resolver.getUUID(), revision, future, stillCurrent));
        try {
            if (!stillCurrent.getAsBoolean() || !isOnline.test(resolver.getUUID())) return Optional.empty();
            transport.send(resolver,
                    new ResolvePlaybackRequestMessage(requestId, revision, requestedMusic));
            ResolvePlaybackResultMessage result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!result.success() || !stillCurrent.getAsBoolean()) {
                return Optional.empty();
            }
            return Optional.of(new PlaybackResolution(result.musicDetail(), result.resourceInfo()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException error) {
            return Optional.empty();
        } finally {
            pending.remove(requestId);
        }
    }

    public void accept(IPlayerClient resolver, ResolvePlaybackResultMessage result) {
        PendingResolve waiting = pending.get(result.requestId());
        if (waiting == null || !waiting.stillCurrent().getAsBoolean() || !isOnline.test(resolver.getUUID())
                || !waiting.resolverId().equals(resolver.getUUID())
                || waiting.revision() != result.revision()) {
            return;
        }
        waiting.future().complete(result);
    }

    /** Wake pending resolves when their player leaves, without waiting for network timeout. */
    public void cancelUnavailableResolvers() {
        pending.forEach((id, waiting) -> {
            if (!isOnline.test(waiting.resolverId()) && pending.remove(id, waiting)) {
                waiting.future().completeExceptionally(
                        new IllegalStateException("Playback resolver disconnected"));
            }
        });
    }

    public void cancelStaleRequests() {
        pending.forEach((id, waiting) -> {
            if (!waiting.stillCurrent().getAsBoolean() && pending.remove(id, waiting)) {
                waiting.future().completeExceptionally(
                        new IllegalStateException("Public playback resolve was superseded"));
            }
        });
    }

    public static List<IPlayerClient> prioritizeResolvers(List<IPlayerClient> players, UUID ownerId) {
        return players.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator
                        .comparing((IPlayerClient player) -> !player.getUUID().equals(ownerId))
                        .thenComparing(player -> player.getUUID().toString()))
                .toList();
    }

    public static List<IPlayerClient> eligibleResolvers(List<IPlayerClient> players,
                                                        MusicDetail requestedMusic) {
        UUID ownerId = requestedMusic.getPusherInfo().getPlayerUUID();
        List<IPlayerClient> ordered = prioritizeResolvers(players, ownerId);
        if (!requestedMusic.isCloudSource()) {
            return ordered;
        }
        return ordered.stream()
                .filter(player -> player.getUUID().equals(ownerId))
                .toList();
    }

    @FunctionalInterface
    public interface ResolveTransport {
        void send(IPlayerClient resolver, ResolvePlaybackRequestMessage request);
    }

    private record PendingResolve(UUID resolverId, int revision,
                                  CompletableFuture<ResolvePlaybackResultMessage> future,
                                  BooleanSupplier stillCurrent) {
    }
}
