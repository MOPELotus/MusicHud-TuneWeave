package indi.mopelotus.musichud.client.ui.hud.pipelines;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glBindBufferRange;

public class HudShaderProgram implements AutoCloseable {
    @Getter
    private int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public HudShaderProgram(int programId) {
        this.programId = programId;
    }

    public int getUniformOrSamplerLocation(String name) {
        return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    @Override public void close() {
        if (programId != 0) {
            glDeleteProgram(programId);
            programId = 0;
            uniformLocations.clear();
        }
    }

    public static final class UniformBufferHandle {
        @Getter
        private int uboId;
        @Getter
        private final int bindingPoint;
        private final int size;

        public UniformBufferHandle(int uboId, int bindingPoint, int size) {
            this.uboId = uboId;
            this.bindingPoint = bindingPoint;
            this.size = size;
        }

        public static UniformBufferHandle createAndUpload(int bindingPoint, ByteBuffer data) {
            int dataSize = data.remaining();
            int[] ubo = new int[1];
            glGenBuffers(ubo);
            int uboId = ubo[0];
            glBindBuffer(GL_UNIFORM_BUFFER, uboId);
            glBufferData(GL_UNIFORM_BUFFER, data, GL_DYNAMIC_DRAW);
            glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, uboId, 0, dataSize);
            return new UniformBufferHandle(uboId, bindingPoint, dataSize);
        }

        public void upload(ByteBuffer data) {
            data.rewind();
            glBindBuffer(GL_UNIFORM_BUFFER, uboId);
            glBufferSubData(GL_UNIFORM_BUFFER, 0, data);
            glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, uboId, 0, size);
        }

        public void bind() {
            glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, uboId, 0, size);
        }

        public void delete() {
            if (uboId != 0) { glDeleteBuffers(uboId); uboId = 0; }
        }
    }
}
