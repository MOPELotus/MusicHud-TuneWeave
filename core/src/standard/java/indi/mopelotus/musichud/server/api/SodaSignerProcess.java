package indi.mopelotus.musichud.server.api;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A process lease owns both children, including cancellation while the main child is being created. */
final class SodaSignerProcess extends Process {
    @FunctionalInterface interface Starter { Process start() throws IOException; }
    private final Process signer;
    private volatile Process main;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CompletableFuture<Process> ready = new CompletableFuture<>();
    private final CompletableFuture<Process> exit = new CompletableFuture<>();
    SodaSignerProcess(Process signer, Starter starter, BooleanSupplier healthy) {
        this.signer = signer;
        signer.onExit().thenRun(this::destroy);
        Thread.ofVirtual().name("MHWorker-SodaSigner").start(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!stopping.get() && signer.isAlive() && !healthy.getAsBoolean()) {
                    if (System.nanoTime() >= deadline) throw new IOException("Soda signer startup timed out");
                    Thread.sleep(100);
                }
                if (stopping.get() || !signer.isAlive()) throw new CancellationException();
                main = starter.start();
                if (stopping.get()) main.destroyForcibly();
                ready.complete(main); main.waitFor();
            } catch (Exception error) {
                ready.completeExceptionally(new IOException("Managed TuneWeave/Soda signer startup failed"));
            } finally {
                stopping.set(true);
                Process running = main;
                if (running != null && running.isAlive()) running.destroyForcibly();
                signer.destroyForcibly();
                // Do not release the lease until all owned native processes are actually gone.
                CompletableFuture<?> a = running == null ? CompletableFuture.completedFuture(null) : running.onExit();
                CompletableFuture.allOf(a, signer.onExit()).whenComplete((ignored, failure) -> exit.complete(this));
            }
        });
    }
    private Process availableMain() { try { return ready.join(); } catch (CompletionException error) { return null; } }
    @Override public InputStream getInputStream() { Process p = availableMain(); return p == null ? InputStream.nullInputStream() : p.getInputStream(); }
    @Override public InputStream getErrorStream() { Process p = availableMain(); return p == null ? InputStream.nullInputStream() : p.getErrorStream(); }
    @Override public OutputStream getOutputStream() { Process p = availableMain(); return p == null ? OutputStream.nullOutputStream() : p.getOutputStream(); }
    @Override public int waitFor() throws InterruptedException { try { exit.get(); } catch (ExecutionException impossible) { throw new AssertionError(impossible); } return exitValue(); }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        try { exit.get(timeout, unit); return true; } catch (TimeoutException pending) { return false; }
        catch (ExecutionException impossible) { throw new AssertionError(impossible); }
    }
    @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return main == null ? 1 : main.exitValue(); }
    @Override public boolean isAlive() { return !exit.isDone(); }
    @Override public CompletableFuture<Process> onExit() { return exit.copy(); }
    @Override public void destroy() { stopping.set(true); signer.destroy(); Process p = main; if (p != null) p.destroy(); }
    @Override public Process destroyForcibly() { stopping.set(true); signer.destroyForcibly(); Process p = main; if (p != null) p.destroyForcibly(); return this; }
}
