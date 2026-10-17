package indi.mopelotus.musichud.client.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the host's shutdown state when available, while retaining the older host's finish path. */
public final class UiShutdownRequest {
    private final AtomicBoolean requested = new AtomicBoolean();

    public void request(Object host, Runnable legacyFinish) {
        if (!requested.compareAndSet(false, true)) return;
        for (Class<?> type = host.getClass(); type != Object.class; type = type.getSuperclass()) {
            final Method method;
            try {
                method = type.getDeclaredMethod("requestShutdown");
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(host);
                return;
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot request ModernUI shutdown", error);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error fatal) throw fatal;
                throw new IllegalStateException("ModernUI shutdown failed", cause);
            }
        }
        legacyFinish.run();
    }
}
