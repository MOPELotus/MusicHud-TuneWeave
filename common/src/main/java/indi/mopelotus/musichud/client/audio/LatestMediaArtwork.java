package indi.mopelotus.musichud.client.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns only temporary files returned by our artwork loader; old downloads cannot replace a new track. */
final class LatestMediaArtwork {
    private final Executor delivery;
    private long generation;
    private Path active;

    LatestMediaArtwork(Executor delivery) { this.delivery = delivery; }

    synchronized void reset() {
        generation++;
        delete(active);
        active = null;
    }

    synchronized void load(Supplier<CompletableFuture<Path>> loader, BooleanSupplier current, Consumer<Path> publish) {
        long ticket = generation;
        loader.get().thenAccept(path -> {
            if (path == null) return;
            try {
                delivery.execute(() -> {
                    synchronized (this) {
                        if (ticket != generation || !current.getAsBoolean()) { delete(path); return; }
                        delete(active);
                        active = path;
                        publish.accept(path);
                    }
                });
            } catch (RuntimeException rejected) { delete(path); }
        });
    }

    private static void delete(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (java.io.IOException ignored) {}
    }
}
