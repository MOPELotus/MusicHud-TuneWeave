package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.interfaces.Unregister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static indi.mopelotus.musichud.server.api.ApiServerManager.BinaryApiServerStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiServerManagerTest {
    @TempDir Path directory;

    @Test
    void stoppedQueuedLaunchCannotStartAndDoesNotBlockNextGeneration() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        fixture.manager.stopApiServer();
        fixture.executor.runAll();
        assertTrue(fixture.processes.isEmpty());
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        assertEquals(1, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void lateAvailabilityAfterStopCannotPublishRunningOrStartProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.probe = () -> {
            fixture.manager.stopApiServer();
            return true;
        };
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertTrue(fixture.processes.isEmpty());
        assertFalse(fixture.statuses.contains(RUNNING));
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void oldAvailabilityCannotOverwriteAReplacementIntent() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.probe = () -> {
            fixture.manager.restartApiServer();
            fixture.probe = () -> fixture.healthy;
            return true;
        };
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        assertFalse(fixture.statuses.contains(RUNNING));
        fixture.launchHealthy();
        assertEquals(1, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void repeatedRestartsWaitForOldExitAndOnlyNewestOneLaunches() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        fixture.manager.restartApiServer();
        fixture.manager.restartApiServer();
        fixture.manager.restartApiServer();
        assertEquals(1, fixture.processes.size());
        old.exit(0);
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.activeHooks);
    }

    @Test
    void stopCancelsRestartWaitingOnOldProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        fixture.manager.restartApiServer();
        fixture.manager.stopApiServer();
        int afterStop = fixture.statuses.size();
        old.exit(0);
        fixture.executor.runAll();
        assertEquals(1, fixture.processes.size());
        assertFalse(fixture.statuses.subList(afterStop, fixture.statuses.size()).contains(RUNNING));
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void lateCreatedProcessIsTerminatedBeforeNewIntentStarts() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.onLaunch = () -> {
            fixture.manager.stopApiServer();
            fixture.manager.restartApiServer();
        };
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        assertEquals(1, fixture.processes.size());
        StubProcess old = fixture.processes.getFirst();
        assertEquals(1, old.destroyCalls);
        fixture.executor.runNext();
        assertFalse(old.isAlive());
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.cleaned.size());
    }

    @Test
    void lateHealthyProcessProbeCannotPublishOrCleanAfterStop() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        fixture.executor.runNext();
        fixture.executor.runNext();
        fixture.probe = () -> {
            fixture.manager.stopApiServer();
            return true;
        };
        fixture.executor.runAll();
        assertTrue(fixture.cleaned.isEmpty());
        assertFalse(fixture.statuses.contains(RUNNING));
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void runningListenerStoppingTheProcessPreventsManagedCleanup() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.getApiStatusListeners().add(status -> {
            if (status == RUNNING) fixture.manager.stopApiServer();
        });
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        fixture.healthy = true;
        fixture.executor.runAll();
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertFalse(fixture.processes.getFirst().isAlive());
        assertTrue(fixture.cleaned.isEmpty());
    }

    @Test
    void housekeepingFailureDoesNotTerminateAHealthyProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.cleanupFails = true;
        fixture.manager.restartApiServer();
        StubProcess process = fixture.launchHealthy();
        assertTrue(process.isAlive());
        assertEquals(0, process.destroyCalls);
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void oldExitCompletionCannotStopOrRestartNewProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        old.deferExitNotification = true;
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        assertFalse(old.isAlive());
        fixture.launchHealthy();
        int published = fixture.statuses.size();
        old.exitFuture.completeExceptionally(new IOException("late old observer failure"));
        fixture.executor.runAll();
        assertEquals(published, fixture.statuses.size());
        assertEquals(2, fixture.processes.size());
        assertTrue(fixture.processes.getLast().isAlive());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void cancelledOldExitObserverStillTerminatesOwnedProcessBeforeRestart() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        fixture.manager.restartApiServer();
        old.exitFuture.cancel(false);
        fixture.executor.runNext();
        assertFalse(old.isAlive());
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void cancelledProbeAndRejectedSubmissionLeaveNoPendingStartAndCanRecover() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.probe = () -> { throw new CancellationException("cancelled probe"); };
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        fixture.executor.reject = true;
        fixture.manager.restartApiServer();
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        fixture.executor.reject = false;
        fixture.probe = () -> fixture.healthy;
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        assertEquals(1, fixture.processes.size());
    }

    @Test
    void oldLaunchFailureDoesNotCancelNewGeneration() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.launchFailure = new IOException("old process creation failed");
        fixture.onLaunch = fixture.manager::restartApiServer;
        fixture.manager.restartApiServer();
        fixture.executor.runNext();
        fixture.launchHealthy();
        assertEquals(1, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.activeHooks);
    }

    @Test
    void automaticFailuresReachLimitAndManualRestartResetsAttempts() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        for (int index = 0; index < 5; index++) {
            StubProcess process = fixture.launchHealthy();
            assertEquals(index + 1, fixture.processes.size());
            process.exit(1);
        }
        fixture.executor.runAll();
        assertEquals(5, fixture.processes.size());
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        assertEquals(6, fixture.processes.size());
    }

    @Test
    void repeatedCreationFailuresAlsoRespectRetryLimit() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.failEveryLaunch = true;
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertEquals(5, fixture.launchCalls);
        assertTrue(fixture.processes.isEmpty());
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
    }

    @Test
    void terminationIsBoundedAndUnkillableOldProcessPreventsOverlap() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        old.gracefulExit = false;
        old.forcedExit = false;
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertEquals(List.of(1_000L, 1_000L), old.waits);
        assertEquals(1, old.forceCalls);
        assertEquals(1, fixture.processes.size());
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        old.exit(137);
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
    }

    @Test
    void interruptedOldTerminationConfirmsForcedExitWithoutCancellingReplacement() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess old = fixture.launchHealthy();
        old.interruptFirstWait = true;
        fixture.manager.restartApiServer();
        try {
            fixture.executor.runNext();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(old.isAlive());
        assertEquals(1, old.forceCalls);
        assertEquals(List.of(1_000L, 1_000L), old.waits);
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void staleShutdownListenerCannotStopNewGenerationAndListenersAreReleased() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        Runnable oldListener = fixture.hooks.getFirst();
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        oldListener.run();
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.activeHooks);
        fixture.hooks.getLast().run();
        fixture.executor.runAll();
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
    }

    @Test
    void jvmHookCompletesForcedTerminationBeforeReturning() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess process = fixture.launchHealthy();
        process.gracefulExit = false;
        fixture.jvmHooks.getLast().run();
        assertEquals(1, process.forceCalls);
        assertFalse(process.isAlive());
        assertEquals(List.of(1_000L, 1_000L), process.waits);
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
        assertEquals(0, fixture.activeJvmHooks);
        fixture.executor.runAll();
        assertEquals(1, fixture.processes.size());
    }

    @Test
    void lifecycleStopRemainsNonblockingAndKeepsJvmFallbackUntilProcessExits() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        StubProcess process = fixture.launchHealthy();
        process.gracefulExit = false;
        fixture.hooks.getLast().run();
        assertTrue(process.isAlive());
        assertTrue(process.waits.isEmpty());
        assertEquals(0, fixture.activeHooks);
        assertEquals(1, fixture.activeJvmHooks);
        fixture.jvmHooks.getLast().run();
        assertFalse(process.isAlive());
        assertEquals(1, process.forceCalls);
        assertEquals(0, fixture.activeJvmHooks);
        fixture.executor.runAll();
    }

    @Test
    void retiredJvmHookCannotStopTheReplacementProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        Runnable retiredHook = fixture.jvmHooks.getLast();
        StubProcess old = fixture.launchHealthy();
        old.gracefulExit = false;
        fixture.manager.restartApiServer();
        retiredHook.run();
        assertFalse(old.isAlive());
        StubProcess replacement = fixture.launchHealthy();
        retiredHook.run();
        assertTrue(replacement.isAlive());
        assertEquals(0, replacement.destroyCalls);
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.activeJvmHooks);
    }

    @Test
    void jvmStopCannotBeReversedByAStatusListenerOrQueuedLaunch() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        fixture.manager.getApiStatusListeners().add(status -> {
            if (status == STOPPED) fixture.manager.restartApiServer();
        });
        fixture.jvmHooks.getLast().run();
        fixture.executor.runAll();
        assertTrue(fixture.processes.isEmpty());
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeJvmHooks);
    }

    @Test
    void jvmHookWaitsOutsideManagerLockForInFlightCreationAndThenForcesExit() throws Exception {
        Fixture fixture = new Fixture(directory);
        CountDownLatch stopping = new CountDownLatch(1);
        AtomicReference<Thread> hookThread = new AtomicReference<>();
        fixture.manager.restartApiServer();
        fixture.manager.getApiStatusListeners().add(status -> {
            if (status == STOPPED) stopping.countDown();
        });
        fixture.forceTermination = true;
        fixture.onLaunch = () -> {
            hookThread.set(Thread.ofPlatform().start(fixture.jvmHooks.getLast()));
            try {
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        };
        fixture.executor.runNext();
        hookThread.get().join(5_000);
        assertFalse(hookThread.get().isAlive(), "JVM hook must release manager lock while awaiting creation");
        StubProcess process = fixture.processes.getFirst();
        assertFalse(process.isAlive());
        assertEquals(1, process.forceCalls);
        assertEquals(List.of(1_000L, 1_000L), process.waits);
        assertFalse(fixture.statuses.contains(RUNNING));
        assertEquals(0, fixture.activeJvmHooks);
        fixture.executor.runAll();
    }

    @Test
    void externallyAvailableApiRemainsRunningUntilStopWithoutOwningAProcess() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.healthy = true;
        fixture.manager.restartApiServer();
        fixture.executor.runAll();
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertTrue(fixture.processes.isEmpty());
        fixture.manager.stopApiServer();
        assertEquals(STOPPED, fixture.manager.getBinaryApiServerStatus());
        assertEquals(0, fixture.activeHooks);
    }

    @Test
    void stopListenerStartingANewIntentDoesNotRetireThatNewAttempt() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        boolean[] restarted = {false};
        fixture.manager.getApiStatusListeners().add(status -> {
            if (status == STOPPED && !restarted[0]) {
                restarted[0] = true;
                fixture.manager.restartApiServer();
            }
        });
        fixture.manager.stopApiServer();
        fixture.launchHealthy();
        assertEquals(2, fixture.processes.size());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
    }

    @Test
    void lateLifecycleDetachCannotOverwriteANewlyRunningGeneration() throws IOException {
        Fixture fixture = new Fixture(directory);
        fixture.manager.restartApiServer();
        fixture.launchHealthy();
        fixture.onLifecycleDetach = () -> {
            fixture.manager.restartApiServer();
            fixture.launchHealthy();
        };
        fixture.manager.stopApiServer();
        assertEquals(2, fixture.processes.size());
        assertTrue(fixture.processes.getLast().isAlive());
        assertEquals(RUNNING, fixture.manager.getBinaryApiServerStatus());
        assertEquals(1, fixture.activeHooks);
    }

    private static final class Fixture {
        final ManualExecutor executor = new ManualExecutor();
        final List<StubProcess> processes = new ArrayList<>();
        final List<Path> cleaned = new ArrayList<>();
        final List<Runnable> hooks = new ArrayList<>();
        final List<Runnable> jvmHooks = new ArrayList<>();
        final List<ApiServerManager.BinaryApiServerStatus> statuses = new ArrayList<>();
        final ApiServerManager manager;
        boolean healthy;
        BooleanSupplier probe = () -> healthy;
        Runnable onLaunch;
        Runnable onLifecycleDetach;
        IOException launchFailure;
        boolean failEveryLaunch;
        boolean cleanupFails;
        boolean forceTermination;
        int activeHooks;
        int activeJvmHooks;
        int launchCalls;

        Fixture(Path directory) throws IOException {
            Path binary = Files.writeString(directory.resolve("tuneweave"), "test executable placeholder");
            assertTrue(binary.toFile().setExecutable(true));
            ServerConfig config = (ServerConfig) Proxy.newProxyInstance(ServerConfig.class.getClassLoader(),
                    new Class<?>[]{ServerConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getServerApiBinaryExecutablePath" -> binary.toString();
                        case "getPort" -> 3000;
                        case "getStartupBinaryApiServerWhenLaunch" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            manager = new ApiServerManager(config, executor, () -> probe.getAsBoolean(), executable -> {
                launchCalls++;
                Runnable callback = onLaunch;
                onLaunch = null;
                if (callback != null) callback.run();
                IOException failure = launchFailure;
                launchFailure = null;
                if (failure != null) throw failure;
                if (failEveryLaunch) throw new IOException("controlled process creation failure");
                StubProcess process = new StubProcess();
                process.gracefulExit = !forceTermination;
                processes.add(process);
                return process;
            }, executable -> {
                if (cleanupFails) throw new IllegalStateException("controlled cleanup failure");
                cleaned.add(executable);
            }, (listener, jvmListener) -> {
                hooks.add(listener);
                jvmHooks.add(jvmListener);
                activeHooks++;
                activeJvmHooks++;
                Unregister lifecycle = new Unregister() {
                    boolean removed;
                    @Override public void unregister() {
                        if (!removed) activeHooks--;
                        removed = true;
                        Runnable callback = onLifecycleDetach;
                        onLifecycleDetach = null;
                        if (callback != null) callback.run();
                    }
                };
                Unregister jvm = new Unregister() {
                    boolean removed;
                    @Override public void unregister() {
                        if (!removed) activeJvmHooks--;
                        removed = true;
                    }
                };
                return new ApiServerManager.ShutdownHooks(lifecycle, jvm);
            }, millis -> { throw new AssertionError("Unexpected health-check sleep"); });
            manager.getApiStatusListeners().add(statuses::add);
        }

        StubProcess launchHealthy() {
            int expected = processes.size() + 1;
            healthy = false;
            for (int count = 0; processes.size() < expected && count < 20; count++) executor.runNext();
            assertEquals(expected, processes.size());
            healthy = true;
            executor.runAll();
            assertEquals(RUNNING, manager.getBinaryApiServerStatus());
            return processes.getLast();
        }
    }

    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("controlled rejection");
            tasks.add(command);
        }
        void runNext() {
            Runnable next = tasks.poll();
            assertNotNull(next, "Expected pending production task");
            next.run();
        }
        void runAll() {
            for (int count = 0; !tasks.isEmpty() && count < 100; count++) runNext();
            assertTrue(tasks.isEmpty(), "Production work must terminate without an unbounded retry loop");
        }
    }

    private static final class StubProcess extends Process {
        final CompletableFuture<Process> exitFuture = new CompletableFuture<>();
        final List<Long> waits = new ArrayList<>();
        boolean alive = true;
        boolean gracefulExit = true;
        boolean forcedExit = true;
        boolean deferExitNotification;
        boolean interruptFirstWait;
        int destroyCalls;
        int forceCalls;
        int code;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { throw new AssertionError("Unbounded waitFor is forbidden"); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waits.add(unit.toMillis(timeout));
            if (interruptFirstWait) {
                interruptFirstWait = false;
                throw new InterruptedException("controlled old termination interruption");
            }
            if (alive && gracefulExit) exit(0);
            return !alive;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return code;
        }
        @Override public void destroy() { destroyCalls++; }
        @Override public Process destroyForcibly() {
            forceCalls++;
            if (forcedExit) exit(137);
            return this;
        }
        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return exitFuture; }
        void exit(int exitCode) {
            alive = false;
            code = exitCode;
            if (!deferExitNotification) exitFuture.complete(this);
        }
    }
}
