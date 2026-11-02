package indi.mopelotus.musichud.client.companion;

import indi.mopelotus.musichud.MusicHud;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded stack sampling for transition/exit stalls; never touches a graphics context. */
public final class CompanionDiagnostics {
    private static final AtomicReference<Phase> ACTIVE = new AtomicReference<>();
    private static final AtomicInteger SLOW_REPORTS = new AtomicInteger();
    private static volatile Thread watcher;
    private static volatile boolean finished;
    private static volatile boolean shuttingDown;
    private record Phase(String name, Thread thread, long started) {}

    private CompanionDiagnostics() {}

    private static synchronized boolean arm() {
        if (finished) return false;
        if (watcher == null) {
            if (!CompanionBridge.enabled()) return false;
            watcher = Thread.ofPlatform().daemon().name("Companion-stall-diagnostics").unstarted(CompanionDiagnostics::watch);
            watcher.start();
        }
        return true;
    }

    public static void measure(String name, Runnable action) {
        if (!arm()) { action.run(); return; }
        Phase phase = new Phase(name, Thread.currentThread(), System.nanoTime());
        Phase previous = ACTIVE.getAndSet(phase);
        try { action.run(); }
        finally {
            long elapsed = System.nanoTime() - phase.started;
            ACTIVE.compareAndSet(phase, previous != null ? previous
                    : new Phase("between game frames", phase.thread, System.nanoTime()));
            if (elapsed >= 150_000_000L && SLOW_REPORTS.getAndIncrement() < 32) {
                MusicHud.LOGGER.warn("Companion diagnostics: {} took {} ms", name, elapsed / 1_000_000);
            }
        }
    }

    public static void beginShutdown() {
        if (!arm()) return;
        shuttingDown = true;
        ACTIVE.set(new Phase("Minecraft.close", Thread.currentThread(), System.nanoTime()));
        MusicHud.LOGGER.info("Companion diagnostics: Minecraft.close entered");
    }

    public static void finishShutdown() {
        finished = true;
        ACTIVE.set(null);
        Thread current = watcher;
        if (current != null) {
            MusicHud.LOGGER.info("Companion diagnostics: Minecraft.close completed");
            current.interrupt();
        }
    }

    private static void watch() {
        Phase reported = null;
        int samples = 0;
        boolean shutdownSample = false;
        while (!finished) {
            try { Thread.sleep(100); }
            catch (InterruptedException ignored) { return; }
            Phase phase = ACTIVE.get();
            if (phase == null || phase == reported || System.nanoTime() - phase.started < 500_000_000L) continue;
            if (samples >= 6 && (!shuttingDown || shutdownSample)) continue;
            reported = phase;
            samples++;
            if (shuttingDown) shutdownSample = true;
            StringBuilder dump = new StringBuilder("Companion diagnostics: stalled in ")
                    .append(phase.name).append(" for ").append((System.nanoTime() - phase.started) / 1_000_000).append(" ms");
            var info = java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(phase.thread.threadId());
            if (info != null && info.getLockName() != null) {
                dump.append("; lock=").append(info.getLockName()).append("; owner=")
                        .append(info.getLockOwnerName()).append('/').append(info.getLockOwnerId());
            }
            // Platform-thread stacks only; this API does not enumerate virtual audio workers.
            // No thread-local data, playback state, request URLs or arguments.
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                String name = thread.getName();
                if (thread == phase.thread || name.equals("main") || name.contains("Render")
                        || name.contains("UI") || name.contains("Ixeris") || name.contains("Audio")
                        || name.startsWith("MHWorker")) {
                    dump.append('\n').append('"').append(name).append("\" ").append(thread.getState());
                    for (int i = 0; i < Math.min(40, stack.length); i++) dump.append("\n    at ").append(stack[i]);
                }
            });
            if (!finished && ACTIVE.get() == phase) MusicHud.LOGGER.warn("{}", dump);
        }
    }
}
