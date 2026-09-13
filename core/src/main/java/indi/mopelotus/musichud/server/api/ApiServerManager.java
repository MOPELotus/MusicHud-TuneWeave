package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@RegisterMark
public class ApiServerManager implements ServerRegister {
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_TRIES = 5;
    private static final long TERMINATION_WAIT_MILLIS = 1_000;
    @Getter private static ApiServerManager instance;

    private final ServerConfig serverConfig;
    private final Executor executor;
    private final BooleanSupplier available;
    private final ProcessLauncher launcher;
    private final Consumer<Path> cleanup;
    private final ShutdownRegistrar shutdownRegistration;
    private final Sleeper sleeper;
    private final Logger apiLogger = LogManager.getLogger(MusicHud.LOGGER_BASE_NAME + "/API");
    @Getter private final List<Consumer<BinaryApiServerStatus>> apiStatusListeners = new CopyOnWriteArrayList<>();
    @Getter private volatile BinaryApiServerStatus binaryApiServerStatus = BinaryApiServerStatus.STOPPED;

    // Guarded by this. An attempt owns its process even after a newer user intent supersedes it.
    private long generation;
    private boolean wanted;
    private int triedCount;
    private boolean initialized;
    private boolean jvmStopping;
    private Attempt attempt;
    private ShutdownSession shutdownHook;

    public ApiServerManager() {
        this(ServerConfig.getInstance(), MusicHud.EXECUTOR, TuneWeaveApiClient::isAvailable,
                null, null, ApiServerManager::registerShutdown, Thread::sleep);
    }

    ApiServerManager(ServerConfig serverConfig, Executor executor, BooleanSupplier available,
                     ProcessLauncher launcher, Consumer<Path> cleanup,
                     ShutdownRegistrar shutdownRegistration, Sleeper sleeper) {
        this.serverConfig = Objects.requireNonNull(serverConfig);
        this.executor = Objects.requireNonNull(executor);
        this.available = Objects.requireNonNull(available);
        this.launcher = launcher == null ? this::launchProcess : launcher;
        this.cleanup = cleanup == null ? this::cleanupManagedBinaries : cleanup;
        this.shutdownRegistration = Objects.requireNonNull(shutdownRegistration);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @Override
    public synchronized void register() {
        instance = this;
        if (initialized) return;
        initialized = true;
        if (serverConfig.getStartupBinaryApiServerWhenLaunch()) restartApiServer();
    }

    public synchronized void stopApiServer() {
        Attempt previous = attempt;
        long stoppedGeneration = ++generation;
        wanted = false;
        removeShutdownHook(previous);
        setApiStatus(BinaryApiServerStatus.STOPPED, stoppedGeneration);
        retire(previous);
    }

    public synchronized void restartApiServer() {
        if (jvmStopping) return;
        long requestedGeneration = ++generation;
        wanted = true;
        triedCount = 0;
        Attempt previous = attempt;
        removeShutdownHook(previous);
        setApiStatus(BinaryApiServerStatus.STOPPED, requestedGeneration);
        if (!isWanted(requestedGeneration)) return;
        try {
            ShutdownSession session = new ShutdownSession(requestedGeneration);
            ShutdownHooks registration = shutdownRegistration.register(
                    () -> stopGeneration(requestedGeneration), () -> stopAtJvmShutdown(session));
            session.hooks = registration;
            if (!isWanted(requestedGeneration)) {
                registration.lifecycle().unregister();
                registration.jvm().unregister();
                return;
            }
            shutdownHook = session;
        } catch (RuntimeException error) {
            failIntent(requestedGeneration, "Failed to register TuneWeave shutdown handling", error);
            retire(previous);
            return;
        }
        retire(previous);
        queueAfter(previous, requestedGeneration);
    }

    private synchronized void stopGeneration(long expectedGeneration) {
        if (generation == expectedGeneration) stopApiServer();
    }

    private void stopAtJvmShutdown(ShutdownSession session) {
        Attempt previous;
        synchronized (this) {
            if (generation == session.generation) {
                jvmStopping = true;
                previous = attempt;
                stopApiServer();
            } else {
                // A retired hook remains responsible only for its original process.
                previous = session.retiring;
            }
        }
        if (previous == null) return;
        Process running;
        synchronized (this) {
            running = previous.process;
        }
        if (running == null) {
            try {
                running = previous.createdFuture.get(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | CancellationException failure) {
                apiLogger.warn("TuneWeave process creation did not settle before JVM shutdown", failure);
            }
            synchronized (this) {
                if (running == null) running = previous.process;
            }
        }
        // Shutdown hooks must complete this bounded wait themselves: the JVM does not
        // wait for virtual-thread termination work after its hooks have returned.
        if (running != null) terminate(previous);
    }

    private boolean isWanted(long expectedGeneration) {
        return wanted && generation == expectedGeneration;
    }

    private boolean isCurrent(Attempt owner) {
        return attempt == owner && !owner.finished && isWanted(owner.generation);
    }

    private void queueAfter(Attempt previous, long requestedGeneration) {
        CompletableFuture<Void> finished = previous == null
                ? CompletableFuture.completedFuture(null) : previous.finishedFuture;
        finished.whenComplete((unused, failure) -> {
            synchronized (this) {
                if (!isWanted(requestedGeneration) || attempt != previous) return;
                if (previous != null && previous.process != null && previous.process.isAlive()) {
                    failIntent(requestedGeneration, "Previous TuneWeave process has not terminated", failure);
                    return;
                }
                Attempt next = new Attempt(requestedGeneration);
                attempt = next;
                submit(next, () -> launch(next));
            }
        });
    }

    private void submit(Attempt owner, Runnable work) {
        try {
            executor.execute(work);
        } catch (RuntimeException error) {
            synchronized (this) {
                if (isCurrent(owner)) failIntent(owner.generation, "TuneWeave worker rejected", error);
                if (owner.process == null) finish(owner, error, false);
                else retire(owner);
            }
        }
    }

    private void launch(Attempt owner) {
        synchronized (this) {
            if (!isCurrent(owner)) {
                finish(owner, null, false);
                return;
            }
        }
        try {
            boolean alreadyAvailable = available.getAsBoolean();
            Path executable;
            synchronized (this) {
                if (!isCurrent(owner)) return;
                if (alreadyAvailable) {
                    setApiStatus(BinaryApiServerStatus.RUNNING, owner.generation);
                    owner.finished = true;
                    owner.finishedFuture.complete(null);
                    return;
                }
                if (triedCount >= MAX_TRIES) {
                    failIntent(owner.generation, "Embedded TuneWeave stopped after maximum startup attempts", null);
                    finish(owner, null, false);
                    return;
                }
                executable = executablePath();
                triedCount++;
                setApiStatus(BinaryApiServerStatus.LAUNCHING, owner.generation);
                if (!isCurrent(owner)) return;
                // A stop during ProcessBuilder.start must await and retire its returned process.
                owner.creationStarted = true;
            }
            Process launched = Objects.requireNonNull(launcher.start(executable), "Launched process");
            synchronized (this) {
                owner.process = launched;
                owner.createdFuture.complete(launched);
                launched.onExit().whenComplete((exited, error) -> processExited(owner, error));
                if (!isCurrent(owner)) {
                    retire(owner);
                    return;
                }
                owner.writer = openLog();
                submit(owner, () -> readConsole(launched.getInputStream(), owner.writer, false));
                submit(owner, () -> readConsole(launched.getErrorStream(), owner.writer, true));
                submit(owner, () -> waitForTuneWeave(owner, executable));
            }
        } catch (Exception error) {
            synchronized (this) {
                if (owner.process != null && owner.process.isAlive()) {
                    if (isCurrent(owner)) failIntent(owner.generation, "Failed to monitor TuneWeave process", error);
                    retire(owner);
                } else {
                    boolean retry = owner.creationStarted && !(error instanceof CancellationException);
                    if (!retry && isCurrent(owner)) failIntent(owner.generation, "TuneWeave launch cancelled or unavailable", error);
                    finish(owner, error, retry);
                }
            }
        }
    }

    private synchronized void processExited(Attempt owner, Throwable failure) {
        if (owner.finished) return;
        if (owner.process.isAlive()) {
            // A cancelled/failed onExit future is not proof that the process has stopped.
            retire(owner);
            return;
        }
        finish(owner, failure, true);
    }

    private void retire(Attempt owner) {
        if (owner == null || owner.finished) return;
        if (!owner.creationStarted) {
            finish(owner, null, false);
            return;
        }
        if (owner.process == null || owner.terminationScheduled) return;
        owner.terminationScheduled = true;
        try {
            owner.process.destroy();
        } catch (RuntimeException error) {
            apiLogger.warn("Failed to request TuneWeave termination", error);
        }
        // The normal executor can reject shutdown work; termination still needs to run.
        try {
            executor.execute(() -> terminate(owner));
        } catch (RuntimeException rejected) {
            Thread.ofVirtual().name("MHWorker-API-Termination").start(() -> terminate(owner));
        }
    }

    private void terminate(Attempt owner) {
        Process running = owner.process;
        Throwable failure = null;
        boolean interrupted = false;
        try {
            if (running.isAlive() && !running.waitFor(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly();
                if (!running.waitFor(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    failure = new IOException("TuneWeave process did not terminate within the shutdown deadline");
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            failure = error;
            running.destroyForcibly();
            try {
                // InterruptedException cleared the flag. Still confirm termination before
                // releasing a replacement; restore the caller's flag after this bounded wait.
                running.waitFor(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException again) {
                failure.addSuppressed(again);
            }
        } catch (RuntimeException error) {
            failure = error;
        } finally {
            if (failure != null) apiLogger.warn("TuneWeave process termination did not finish cleanly", failure);
            synchronized (this) {
                finish(owner, failure, !running.isAlive());
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void finish(Attempt owner, Throwable failure, boolean retry) {
        if (owner.finished) return;
        boolean current = isCurrent(owner);
        owner.finished = true;
        if (owner.process == null) owner.createdFuture.complete(null);
        if (owner.writer != null) owner.writer.close();
        if (current) {
            setApiStatus(BinaryApiServerStatus.STOPPED, owner.generation);
            if (retry && triedCount < MAX_TRIES && (owner.process == null || !owner.process.isAlive())) {
                if (failure != null) apiLogger.warn("TuneWeave startup attempt failed; retrying", failure);
            } else if (retry || failure != null) {
                failIntent(owner.generation, "TuneWeave process stopped without another retry", failure);
                retry = false;
            }
        }
        if (failure == null) owner.finishedFuture.complete(null);
        else owner.finishedFuture.completeExceptionally(failure);
        if (current && retry) queueAfter(owner, owner.generation);
    }

    private void failIntent(long expectedGeneration, String message, Throwable error) {
        if (!isWanted(expectedGeneration)) return;
        wanted = false;
        removeShutdownHook(attempt);
        setApiStatus(BinaryApiServerStatus.STOPPED, expectedGeneration);
        if (error == null) apiLogger.warn(message);
        else apiLogger.warn(message, error);
    }

    private void waitForTuneWeave(Attempt owner, Path executable) {
        try {
            for (int count = 0; count < 120; count++) {
                synchronized (this) {
                    if (!isCurrent(owner) || !owner.process.isAlive()) return;
                }
                boolean healthy = available.getAsBoolean();
                synchronized (this) {
                    if (!isCurrent(owner) || !owner.process.isAlive()) return;
                    if (healthy) {
                        setApiStatus(BinaryApiServerStatus.RUNNING, owner.generation);
                        // Listener callbacks can stop/restart the manager synchronously.
                        if (isCurrent(owner)) {
                            try {
                                cleanup.accept(executable);
                            } catch (RuntimeException failure) {
                                apiLogger.warn("Deferred TuneWeave managed-binary cleanup", failure);
                            }
                        }
                        return;
                    }
                }
                sleeper.sleep(250);
            }
            synchronized (this) {
                if (isCurrent(owner)) retire(owner);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                if (isCurrent(owner)) {
                    failIntent(owner.generation, "TuneWeave health check interrupted", interrupted);
                    retire(owner);
                }
            }
        } catch (RuntimeException error) {
            synchronized (this) {
                if (isCurrent(owner)) {
                    failIntent(owner.generation, "TuneWeave health check failed", error);
                    retire(owner);
                }
            }
        }
    }

    private Path executablePath() throws IOException {
        String configured = serverConfig.getServerApiBinaryExecutablePath();
        Path binary = Paths.get(configured);
        Path windowsBinary = Paths.get(configured + ".exe");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (launchable(windowsBinary, windows)) return windowsBinary;
        if (launchable(binary, windows)) return binary;
        throw new IOException("Configured TuneWeave binary is missing or not executable");
    }

    private static boolean launchable(Path path, boolean windows) {
        return Files.isRegularFile(path) && (windows || Files.isExecutable(path));
    }

    private Process launchProcess(Path executable) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString());
        Map<String, String> environment = builder.environment();
        environment.put("TUNEWEAVE_BIND", "127.0.0.1:" + serverConfig.getPort());
        environment.put("TUNEWEAVE_DATA_DIR", installationDirectory().resolve("tuneweave-data").toString());
        return builder.start();
    }

    private PrintWriter openLog() {
        try {
            Files.createDirectories(getLogDir());
            Path file = getLogDir().resolve("api-server-" + LocalDateTime.now().format(LOG_TIMESTAMP) + ".log");
            return new PrintWriter(new FileWriter(file.toFile(), true), true);
        } catch (IOException error) {
            apiLogger.warn("Failed to create TuneWeave log file", error);
            return null;
        }
    }

    private void readConsole(InputStream stream, PrintWriter writer, boolean error) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (writer != null) writer.println(line);
                log(line, error);
            }
        } catch (IOException failure) {
            apiLogger.debug("TuneWeave process output closed", failure);
        }
    }

    private void cleanupManagedBinaries(Path executable) {
        ApiBinaryUpdateService.CleanupReport report = ApiBinaryUpdateService.getInstance()
                .cleanupObsoleteManagedBinaries(executable.toAbsolutePath().getParent(), executable);
        if (report.failed() > 0 || report.rejected() > 0) {
            apiLogger.warn("Deferred cleanup of {} TuneWeave binaries; rejected {} unsafe manifest entries",
                    report.failed(), report.rejected());
        }
    }

    private static ShutdownHooks registerShutdown(Runnable lifecycleStop, Runnable jvmStop) {
        Thread hook = new Thread(jvmStop, "MusicHud-TuneWeave-API-Shutdown");
        Unregister lifecycle = ICommonEventService.getInstance().registerCommonLifecycleStopping(lifecycleStop);
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (RuntimeException error) {
            lifecycle.unregister();
            throw error;
        }
        return new ShutdownHooks(lifecycle, () -> {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown may already be executing this hook.
            }
        });
    }

    private void removeShutdownHook(Attempt retiring) {
        ShutdownSession previous = shutdownHook;
        shutdownHook = null;
        if (previous != null) {
            previous.retiring = retiring;
            try {
                previous.hooks.lifecycle().unregister();
            } catch (RuntimeException error) {
                apiLogger.warn("Failed to remove TuneWeave shutdown handling", error);
            }
            CompletableFuture<Void> retired = retiring == null
                    ? CompletableFuture.completedFuture(null) : retiring.finishedFuture;
            retired.whenComplete((unused, failure) -> {
                Process running;
                synchronized (this) {
                    running = retiring == null ? null : retiring.process;
                }
                if (running == null || !running.isAlive()) removeJvmHook(previous);
                else running.onExit().whenComplete((exited, exitFailure) -> {
                    if (!running.isAlive()) removeJvmHook(previous);
                });
            });
        }
    }

    private void removeJvmHook(ShutdownSession session) {
        try {
            session.hooks.jvm().unregister();
        } catch (RuntimeException error) {
            apiLogger.warn("Failed to remove TuneWeave JVM shutdown hook", error);
        }
    }

    /** Keep executable, API logs, and TuneWeave data in one installation folder. */
    private Path installationDirectory() {
        Path absolute = Paths.get(serverConfig.getServerApiBinaryExecutablePath()).toAbsolutePath().normalize();
        Path parent = Files.isDirectory(absolute) ? absolute : absolute.getParent();
        return parent == null ? Paths.get("musichud-tuneweave").toAbsolutePath() : parent;
    }

    public Path getLogDir() {
        return installationDirectory().resolve("logs");
    }

    public void clearLogs() {
        try (var stream = Files.list(getLogDir())) {
            stream.filter(path -> path.getFileName().toString().endsWith(".log")).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public long[] getLogStats() {
        try { Files.createDirectories(getLogDir()); } catch (IOException ignored) { }
        try (var stream = Files.list(getLogDir())) {
            long[] result = {0, 0};
            stream.filter(path -> path.getFileName().toString().endsWith(".log")).forEach(path -> {
                result[0]++;
                try { result[1] += Files.size(path); } catch (IOException ignored) { }
            });
            return result;
        } catch (IOException ignored) {
            return new long[]{0, 0};
        }
    }

    public void log(String line, boolean error) {
        if (error || line.contains("ERROR")) apiLogger.error(line.replace("[ERROR]", ""));
        else apiLogger.debug(line.replace("[INFO]", ""));
    }

    private void setApiStatus(BinaryApiServerStatus status, long expectedGeneration) {
        if (generation != expectedGeneration) return;
        binaryApiServerStatus = status;
        for (Consumer<BinaryApiServerStatus> listener : apiStatusListeners) {
            if (generation != expectedGeneration || binaryApiServerStatus != status) return;
            try { listener.accept(status); }
            catch (RuntimeException error) { apiLogger.warn("TuneWeave API status listener failed for {}", status, error); }
        }
    }

    @FunctionalInterface interface ProcessLauncher { Process start(Path executable) throws IOException; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface interface ShutdownRegistrar {
        ShutdownHooks register(Runnable lifecycleStop, Runnable jvmStop);
    }
    record ShutdownHooks(Unregister lifecycle, Unregister jvm) { }

    private static final class ShutdownSession {
        final long generation;
        ShutdownHooks hooks;
        Attempt retiring;
        ShutdownSession(long generation) { this.generation = generation; }
    }

    private static final class Attempt {
        final long generation;
        final CompletableFuture<Void> finishedFuture = new CompletableFuture<>();
        final CompletableFuture<Process> createdFuture = new CompletableFuture<>();
        Process process;
        PrintWriter writer;
        boolean creationStarted;
        boolean terminationScheduled;
        boolean finished;
        Attempt(long generation) { this.generation = generation; }
    }

    public enum BinaryApiServerStatus {
        STOPPED, LAUNCHING, RUNNING;
        public String i18nKey() { return MusicHud.MOD_ID + ".text.binaryApiServerStatus." + name(); }
    }
}
