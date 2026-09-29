package indi.mopelotus.musichud.server.api;

import org.apache.logging.log4j.Level;
import java.util.regex.Pattern;

/** stderr is a transport; TuneWeave writes normal INFO messages there too. */
record TuneWeaveConsoleLine(Level level, String text) {
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern PREFIX = Pattern.compile("^(?:\\[TuneWeave(?:/[^\\]\\r\\n]+)?\\])?\\[(TRACE|DEBUG|INFO|WARN|ERROR)\\]");
    static TuneWeaveConsoleLine parse(String line, boolean stderr) {
        String text = ANSI.matcher(line == null ? "" : line).replaceAll("");
        var match = PREFIX.matcher(text);
        Level level = match.find() ? Level.valueOf(match.group(1)) : stderr ? Level.ERROR : Level.INFO;
        return new TuneWeaveConsoleLine(level, text);
    }
}
