package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

public record TuneWeaveChallengeSession(TuneWeavePlatform platform, String transactionId) {
}
