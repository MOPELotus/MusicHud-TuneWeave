package indi.mopelotus.musichud.utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessageFactory;
import org.apache.logging.log4j.simple.SimpleLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;
import org.apache.logging.log4j.util.PropertiesUtil;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Keeps the shared logging API while routing output through the owning plugin platform. */
public final class PlatformLoggers {
    private static final Level LEVEL = Level.toLevel(System.getProperty(
            "musichud-tuneweave.log.level", "INFO").toUpperCase(Locale.ROOT), Level.INFO);
    private static final ConcurrentHashMap<String, Logger> LOGGERS = new ConcurrentHashMap<>();
    private static final AtomicReference<Sink> OUTPUT = new AtomicReference<>(
            (name, level, message, failure) ->
                    java.util.logging.Logger.getLogger(name).log(julLevel(level), message, failure));

    private PlatformLoggers() {}

    @FunctionalInterface
    public interface Sink {
        void log(String name, Level level, String message, Throwable failure);
    }

    public static Level configuredLevel() { return LEVEL; }

    public static Sink bind(Sink output) { return OUTPUT.getAndSet(Objects.requireNonNull(output)); }

    public static Sink useJul(java.util.logging.Logger logger) {
        Objects.requireNonNull(logger).setLevel(julLevel(LEVEL));
        return bind((name, level, message, failure) ->
                logger.log(julLevel(level), "[" + name + "] " + message, failure));
    }

    public static Logger getLogger(String name) {
        return LOGGERS.computeIfAbsent(name, key -> createLogger(key, LEVEL, OUTPUT::get));
    }

    static Logger createLogger(String name, Level level, Supplier<Sink> output) {
        // The API's own implementation handles all level checks and message overloads.
        // Only the wrapper emits events; no Log4j Core context or provider is created.
        var filter = new SimpleLogger(name, level, false, false, false, false, null,
                ParameterizedMessageFactory.INSTANCE, new PropertiesUtil(new Properties()), System.err);
        filter.setLevel(level);
        return new ExtendedLoggerWrapper(filter, name, ParameterizedMessageFactory.INSTANCE) {
            @Override public void logMessage(String fqcn, Level severity, Marker marker,
                                              Message message, Throwable failure) {
                output.get().log(getName(), severity, message.getFormattedMessage(),
                        failure != null ? failure : message.getThrowable());
            }
        };
    }

    static java.util.logging.Level julLevel(Level level) {
        return switch (level.getStandardLevel()) {
            case OFF -> java.util.logging.Level.OFF;
            case FATAL, ERROR -> java.util.logging.Level.SEVERE;
            case WARN -> java.util.logging.Level.WARNING;
            case INFO -> java.util.logging.Level.INFO;
            case DEBUG -> java.util.logging.Level.FINE;
            case TRACE -> java.util.logging.Level.FINER;
            case ALL -> java.util.logging.Level.ALL;
        };
    }
}
