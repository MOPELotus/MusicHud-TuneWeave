package indi.mopelotus.musichud.client.audio;

import java.util.function.Consumer;
import static indi.mopelotus.musichud.client.audio.StreamAudioPlayer.Status;

/** Latest-request status. Mutations are serialized by the player facade. */
final class PlaybackStatusState {
    private final Consumer<Status> output;
    private volatile Status status = Status.IDLE;
    private long request;
    private boolean preparationFailed, preparing;

    PlaybackStatusState(Consumer<Status> output) { this.output = output; }
    Status get() { return status; }

    long begin() {
        long ticket = invalidate();
        preparing = true;
        publish(Status.BUFFERING);
        return ticket;
    }

    long invalidate() {
        preparationFailed = false;
        preparing = false;
        return ++request;
    }

    void complete(long ticket, Status candidate, Throwable error) {
        if (request != ticket) return;
        preparing = false;
        preparationFailed = error != null;
        publish(preparationFailed ? Status.ERROR : candidate);
    }

    void observe(Status current) {
        // A retained old lane may finish after the latest public session failed to start.
        if (!preparationFailed) publish(preparing ? Status.BUFFERING : current);
    }

    private void publish(Status next) {
        if (status != next) {
            status = next;
            output.accept(next);
        }
    }
}
