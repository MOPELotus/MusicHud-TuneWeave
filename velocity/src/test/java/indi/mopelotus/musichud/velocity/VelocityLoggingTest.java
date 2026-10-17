package indi.mopelotus.musichud.velocity;

import indi.mopelotus.musichud.platform.plugin.velocity.VelocityLogging;
import indi.mopelotus.musichud.utils.PlatformLoggers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VelocityLoggingTest {
    @Test void routesSharedEventsToTheInjectedLoggerWithTheirOriginalSeverityAndCause() {
        var methods = new ArrayList<String>();
        var messages = new ArrayList<String>();
        var failures = new ArrayList<Throwable>();
        Logger nativeLogger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "musichud-platform-logging-test";
                    methods.add(method.getName());
                    messages.add((String) args[0]);
                    failures.add((Throwable) args[1]);
                    return null;
                });
        var previous = PlatformLoggers.bind((name, level, message, failure) -> {});
        try {
            VelocityLogging.install(nativeLogger);
            var logger = (ExtendedLogger) PlatformLoggers.getLogger("shared-component");
            var failure = new IllegalStateException("synthetic failure");
            for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.FATAL)) {
                logger.logMessage(getClass().getName(), level, null, new SimpleMessage("event"), failure);
            }
            assertEquals(List.of("trace", "debug", "info", "warn", "error", "error"), methods);
            assertTrue(messages.stream().allMatch("[shared-component] event"::equals));
            assertEquals(6, failures.size());
            assertTrue(failures.stream().allMatch(error -> error == failure));
        } finally {
            PlatformLoggers.bind(previous);
        }
    }
}
