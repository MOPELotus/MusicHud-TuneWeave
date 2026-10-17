package indi.mopelotus.musichud.utils;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlatformLoggersTest {
    private record Event(String name, Level level, String message, Throwable failure) {}

    private static PlatformLoggers.Sink collect(List<Event> events) {
        return (name, level, message, failure) -> events.add(new Event(name, level, message, failure));
    }

    @Test void preservesParameterizedMessagesAndBothThrowableOverloads() {
        var events = new ArrayList<Event>();
        var logger = PlatformLoggers.createLogger("component", Level.DEBUG, () -> collect(events));
        var failure = new IllegalStateException("synthetic failure");
        logger.info("Session {} has {} clients", "public-session", 3);
        logger.error("Failed {}", "request", failure);
        logger.error(failure);
        assertEquals(new Event("component", Level.INFO, "Session public-session has 3 clients", null), events.get(0));
        assertEquals(new Event("component", Level.ERROR, "Failed request", failure), events.get(1));
        assertSame(failure, events.get(2).failure());
        assertTrue(events.get(2).message().contains("synthetic failure"));
    }

    @Test void disabledLevelsDoNotRenderArgumentsAndOffEmitsNothing() {
        var events = new ArrayList<Event>();
        var logger = PlatformLoggers.createLogger("component", Level.INFO, () -> collect(events));
        Object expensive = new Object() {
            @Override public String toString() { throw new AssertionError("Disabled argument rendered"); }
        };
        logger.debug("{}", expensive);
        logger.trace("{}", expensive);
        assertFalse(logger.isDebugEnabled());
        assertTrue(logger.isInfoEnabled());
        assertEquals(Level.INFO, logger.getLevel());
        var off = PlatformLoggers.createLogger("off", Level.OFF, () -> collect(events));
        off.fatal("must not emit");
        off.error("must not emit");
        assertTrue(events.isEmpty());
    }

    @Test void cachedLoggersFollowTheSinkBoundAfterTheirCreation() {
        var first = new ArrayList<Event>();
        var second = new ArrayList<Event>();
        var active = new AtomicReference<>(collect(first));
        var logger = PlatformLoggers.createLogger("component", Level.TRACE, active::get);
        logger.trace("first");
        active.set(collect(second));
        logger.debug("second");
        assertEquals(List.of(new Event("component", Level.TRACE, "first", null)), first);
        assertEquals(List.of(new Event("component", Level.DEBUG, "second", null)), second);
    }

    @Test void emitsNativeJulRecordsWithComponentNameAndOriginalThrowable() {
        var records = new ArrayList<java.util.logging.LogRecord>();
        var nativeLogger = java.util.logging.Logger.getAnonymousLogger();
        nativeLogger.setUseParentHandlers(false);
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        nativeLogger.addHandler(handler);
        var previous = PlatformLoggers.useJul(nativeLogger);
        try {
            var logger = PlatformLoggers.getLogger("jul-component");
            var failure = new IllegalStateException("synthetic failure");
            logger.warn("Session {} failed", "public-session", failure);
            assertEquals(1, records.size());
            assertEquals(java.util.logging.Level.WARNING, records.getFirst().getLevel());
            assertEquals("[jul-component] Session public-session failed", records.getFirst().getMessage());
            assertSame(failure, records.getFirst().getThrown());
        } finally {
            PlatformLoggers.bind(previous);
            nativeLogger.removeHandler(handler);
        }
    }
}
