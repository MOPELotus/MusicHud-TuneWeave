package indi.mopelotus.musichud.network;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Dedicated server presence only; this query never creates playback membership. */
public final class BackendDeploymentResponder {
    private BackendDeploymentResponder() {}

    public static void respond(boolean dedicated, boolean request, BooleanSupplier current,
                               Consumer<Runnable> execute, Runnable reply) {
        if (!dedicated || !request || !current.getAsBoolean()) return;
        try {
            execute.accept(() -> { if (current.getAsBoolean()) reply.run(); });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // Shutdown may retire the executor between admission and scheduling.
        }
    }
}
