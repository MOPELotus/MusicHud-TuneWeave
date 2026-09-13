package indi.mopelotus.musichud.client.audio;

import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

/** Owns the startup future so a cancelled/obsolete decoder cannot leave its caller waiting. */
final class PlaybackStartGate {
    private CompletableFuture<ZonedDateTime> current;

    synchronized CompletableFuture<ZonedDateTime> begin() {
        cancel();
        current = new CompletableFuture<>();
        return current;
    }
    synchronized void cancel() {
        CompletableFuture<ZonedDateTime> previous = current;
        current = null;
        if (previous != null) previous.cancel(true);
    }
}
