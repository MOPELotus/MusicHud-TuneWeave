package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.interfaces.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static indi.mopelotus.musichud.server.api.ApiServerManager.BinaryApiServerStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiLoginLifecycleTest {
    private final ApiServerManager previous = ApiServerManager.getInstance();

    @AfterEach void restoreManager() throws Exception { install(previous); }

    @Test void alreadyRunningBeforeRegistrationRestoresOnlyOnReadyClientTick() throws Exception {
        ApiServerManager manager = manager();
        install(manager);
        publish(manager, RUNNING);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicInteger restores = new AtomicInteger();
        Thread client = Thread.currentThread();
        Runnable tick = register(ready::get, () -> {
            assertSame(client, Thread.currentThread());
            assertFalse(Thread.holdsLock(manager));
            restores.incrementAndGet();
        });
        assertEquals(0, restores.get());
        tick.run();
        ready.set(true);
        tick.run();
        tick.run();
        assertEquals(1, restores.get());
    }

    @Test void workerRunningBeforeMinecraftExistsOnlyRecordsIntent() throws Exception {
        ApiServerManager manager = manager();
        install(manager);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger clientChecks = new AtomicInteger();
        Runnable tick = register(() -> { clientChecks.incrementAndGet(); return ready.get(); }, restores::incrementAndGet);
        tick.run();
        Thread worker = new Thread(() -> {
            try { publish(manager, RUNNING); } catch (Exception e) { throw new AssertionError(e); }
        });
        worker.start(); worker.join();
        assertEquals(0, clientChecks.get());
        assertEquals(0, restores.get());
        tick.run();
        assertEquals(1, clientChecks.get());
        assertEquals(0, restores.get());
        ready.set(true);
        tick.run();
        assertEquals(1, restores.get());
    }

    @Test void managerRegisteredAfterClientAndLateOldListenerAreHandled() throws Exception {
        install(null);
        AtomicInteger restores = new AtomicInteger();
        Runnable tick = register(() -> true, restores::incrementAndGet);
        tick.run();
        ApiServerManager old = manager();
        install(old);
        tick.run();
        var lateOldCallback = old.getApiStatusListeners().getFirst();
        ApiServerManager current = manager();
        publish(current, RUNNING);
        install(current);
        tick.run();
        assertTrue(old.getApiStatusListeners().isEmpty(), "Detach the old subscription");
        lateOldCallback.accept(STOPPED);
        lateOldCallback.accept(RUNNING);
        tick.run();
        assertEquals(1, restores.get());
        publish(current, STOPPED);
        publish(current, RUNNING);
        tick.run();
        assertEquals(2, restores.get());
    }

    private static ApiServerManager manager() {
        ServerConfig config = (ServerConfig) Proxy.newProxyInstance(ServerConfig.class.getClassLoader(),
                new Class<?>[]{ServerConfig.class}, (proxy, method, args) -> null);
        return new ApiServerManager(config, Runnable::run, () -> true, null, path -> {},
                (stop, shutdown) -> new ApiServerManager.ShutdownHooks(() -> {}, () -> {}), millis -> {});
    }

    private static void install(ApiServerManager manager) throws Exception {
        Field field = ApiServerManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, manager);
    }

    private static void publish(ApiServerManager manager, ApiServerManager.BinaryApiServerStatus status) throws Exception {
        Method method = ApiServerManager.class.getDeclaredMethod("setApiStatus", ApiServerManager.BinaryApiServerStatus.class, long.class);
        method.setAccessible(true);
        synchronized (manager) { method.invoke(manager, status, 0L); }
    }

    private static Runnable register(BooleanSupplier ready, Runnable restore) throws Exception {
        Class<?> lifecycle = Class.forName("indi.mopelotus.musichud.client.services.ApiLoginLifecycle");
        Method method = lifecycle.getDeclaredMethod("register", BooleanSupplier.class, Runnable.class);
        method.setAccessible(true);
        return (Runnable) method.invoke(null, ready, restore);
    }
}
