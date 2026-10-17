package indi.mopelotus.musichud.platform.plugin.velocity;

import indi.mopelotus.musichud.utils.PlatformLoggers;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

/** Routes through Velocity's injected logger and its existing Log4j backend. */
public final class VelocityLogging {
    private VelocityLogging() {}

    public static void install(Logger logger) {
        // Preserve the existing project level property, scoped to this plugin's injected logger.
        Configurator.setLevel(logger.getName(), PlatformLoggers.configuredLevel());
        PlatformLoggers.bind((name, level, message, failure) -> {
            String line = "[" + name + "] " + message;
            switch (level.getStandardLevel()) {
                case FATAL, ERROR -> logger.error(line, failure);
                case WARN -> logger.warn(line, failure);
                case INFO -> logger.info(line, failure);
                case DEBUG -> logger.debug(line, failure);
                case TRACE -> logger.trace(line, failure);
                default -> { }
            }
        });
    }
}
