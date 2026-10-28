package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonNull;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.concurrent.CancellationException;
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
            if (!gateway.hasCredential(platform)) throw loginRequired();
            if (authentication.cachedSession(platform) == null) authentication.loadSession(platform);
            TuneWeaveSession session = authentication.cachedSession(platform);
            if (session == null || !session.authenticated() || session.userId() == null || session.userId().isBlank())
                throw loginRequired();
            entities.requireEpoch(epoch);
            return entities.capture(operation).get();
        });
    }

    /** Cached UI classification has a neutral answer while an account/epoch is changing.
     * Keep the same scope checks as requests, but never retry a rejected read in a new account. */
    boolean readCachedFlag(Supplier<Boolean> operation) {
        try {
            return gateway.capture(entities.capture(operation)).get();
        } catch (CancellationException changedScope) {
            return false;
        }
    }

    private static TuneWeaveException loginRequired() {
        return new TuneWeaveException(
                "Account login is required", 401, "authentication_required", false, JsonNull.INSTANCE);
    }
}
