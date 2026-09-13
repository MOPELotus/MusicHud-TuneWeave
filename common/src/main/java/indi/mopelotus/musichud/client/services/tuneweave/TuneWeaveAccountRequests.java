package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.function.Supplier;

/** Pins credentials before scheduling, but initializes a cold profile before capturing mapping scope. */
final class TuneWeaveAccountRequests {
    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final TuneWeaveAuthenticationService authentication;

    TuneWeaveAccountRequests(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities, TuneWeaveAuthenticationService authentication) {
        this.gateway = gateway; this.entities = entities; this.authentication = authentication;
    }

    <T> Supplier<T> prepare(TuneWeavePlatform platform, Supplier<T> operation) {
        Object epoch = entities.captureEpoch();
        return gateway.capture(() -> {
            entities.requireEpoch(epoch);
            if (!gateway.hasCredential(platform)) throw new IllegalStateException("Account credential is unavailable");
            if (authentication.cachedSession(platform) == null) authentication.loadSession(platform);
            TuneWeaveSession session = authentication.cachedSession(platform);
            if (session == null || !session.authenticated() || session.userId() == null || session.userId().isBlank())
                throw new IllegalStateException("Account session is unavailable");
            entities.requireEpoch(epoch);
            return entities.capture(operation).get();
        });
    }
}
