package indi.mopelotus.musichud.client.ui.hud.pipelines;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Render-thread-owned programs for one resource generation. Shutdown cannot be reversed by late reloads. */
final class HudShaderProgramCache implements AutoCloseable {
    private final Map<String, HudShaderProgram> programs = new HashMap<>();
    private boolean closed;
    HudShaderProgram get(String key, Supplier<HudShaderProgram> factory) {
        if (closed) throw new IllegalStateException("HUD shader cache is closed");
        return programs.computeIfAbsent(key, ignored -> factory.get());
    }
    void invalidate() {
        programs.values().forEach(HudShaderProgram::close);
        programs.clear();
    }
    boolean isClosed() { return closed; }
    @Override public void close() { closed = true; invalidate(); }
}
