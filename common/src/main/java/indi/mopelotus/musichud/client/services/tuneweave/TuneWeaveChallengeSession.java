package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

public record TuneWeaveChallengeSession(TuneWeavePlatform platform, String transactionId, TuneWeaveLoginProgress progress) {
    public TuneWeaveChallengeSession(TuneWeavePlatform platform, String transactionId) {
        this(platform, transactionId, new TuneWeaveLoginProgress("waiting", null, java.util.List.of(), null));
    }
    @Override public String toString() { return "TuneWeaveChallengeSession[platform=" + platform + "]"; }
}
