package indi.mopelotus.musichud.client.ui.hud.pipelines;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.glGetInteger64i;

/** Restores every raw GL binding changed by the legacy HUD, including another renderer's UBO ranges. */
public final class HudGlState implements AutoCloseable {
    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int uniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST);
    private final boolean blend = glIsEnabled(GL_BLEND);
    private final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int[] textures;
    private final Map<Integer, BufferRange> ranges = new LinkedHashMap<>();

    private HudGlState(int textureCount, Collection<Integer> bindings) {
        textures = new int[textureCount];
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
        }
        glActiveTexture(activeTexture);
        for (int binding : bindings) {
            ranges.put(binding, new BufferRange(glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, binding),
                    glGetInteger64i(GL_UNIFORM_BUFFER_START, binding),
                    glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, binding)));
        }
    }

    public static HudGlState capture(int textureCount, Collection<Integer> bindings) {
        return new HudGlState(textureCount, bindings);
    }

    @Override public void close() {
        for (var entry : ranges.entrySet()) {
            BufferRange range = entry.getValue();
            if (range.buffer() != 0 && range.size() > 0) {
                glBindBufferRange(GL_UNIFORM_BUFFER, entry.getKey(), range.buffer(), range.offset(), range.size());
            } else {
                glBindBufferBase(GL_UNIFORM_BUFFER, entry.getKey(), range.buffer());
            }
        }
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
        }
        glActiveTexture(activeTexture);
        glUseProgram(program);
        glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    }

    private record BufferRange(int buffer, long offset, long size) {}
}
