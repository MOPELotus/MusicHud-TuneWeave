package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImagePublicationTest {
    @Test void oldOwnedImageClosesWhileOldSharedImageAndErrorsAreDiscardedWithoutClosingCache() {
        List<Runnable> ui = new ArrayList<>(); List<String> released = new ArrayList<>(), shown = new ArrayList<>();
        var gate = new ImagePublication<String>(ui::add, released::add);
        long old = gate.next();
        gate.complete(old, "temporary", null, true, shown::add, error -> fail());
        gate.complete(old, "cached", null, false, shown::add, error -> fail());
        gate.complete(old, null, new IllegalStateException(), false, shown::add, error -> fail());
        long current = gate.next();
        gate.complete(current, "new", null, false, shown::add, error -> fail());
        ui.forEach(Runnable::run);
        assertEquals(List.of("temporary"), released); assertEquals(List.of("new"), shown);
    }
    @Test void renderingFailureAndRejectedUiStillReleaseOnlyOwnedResultsOnce() {
        List<String> released = new ArrayList<>();
        var inline = new ImagePublication<String>(Runnable::run, released::add);
        inline.complete(inline.next(), "owned", null, true, value -> { throw new IllegalStateException(); }, error -> { throw new IllegalStateException(); });
        var rejected = new ImagePublication<String>(task -> { throw new java.util.concurrent.RejectedExecutionException(); }, released::add);
        rejected.complete(rejected.next(), "pending", null, true, value -> fail(), error -> fail());
        assertEquals(List.of("owned", "pending"), released);
    }
}
