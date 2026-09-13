package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

public record TuneWeaveQrSession(TuneWeavePlatform platform, String transactionId, String url,
                                 String imageDataUrl, String expiresAt) {
}
