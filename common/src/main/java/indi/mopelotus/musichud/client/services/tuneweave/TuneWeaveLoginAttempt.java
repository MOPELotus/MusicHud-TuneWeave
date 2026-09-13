package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

/** Opaque client-local identity. Never serialized or formatted with credential contents. */
public final class TuneWeaveLoginAttempt {
    final TuneWeavePlatform platform;
    String expectedCredential;
    Runnable validateContext;

    TuneWeaveLoginAttempt(TuneWeavePlatform platform, String credential, Runnable validateContext) {
        this.platform = platform;
        this.expectedCredential = credential;
        this.validateContext = validateContext;
    }
}
