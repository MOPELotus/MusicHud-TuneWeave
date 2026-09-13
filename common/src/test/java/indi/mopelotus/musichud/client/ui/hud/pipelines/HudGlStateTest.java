package indi.mopelotus.musichud.client.ui.hud.pipelines;

import org.junit.jupiter.api.*;
import org.lwjgl.opengl.GL;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.glGetInteger64i;

/** Explicit software-OpenGL integration check; no game bootstrap or network access. */
@Tag("integration")
class HudGlStateTest {
    private long window;
    @BeforeEach void context() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        window = glfwCreateWindow(64, 64, "HUD GL state verification", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }
    @AfterEach void closeContext() {
        GL.setCapabilities(null);
        if (window != 0) glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test void restoresOtherRenderersBindingsAndRangesAfterDrawingFailure() {
        int before = program(), during = program();
        int texture0 = glGenTextures(), texture1 = glGenTextures(), scratch = glGenTextures();
        int uniform = glGenBuffers(), generic = glGenBuffers(), replacement = glGenBuffers();
        int alignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
        try {
            glBindBuffer(GL_UNIFORM_BUFFER, uniform);
            glBufferData(GL_UNIFORM_BUFFER, alignment + 64L, GL_DYNAMIC_DRAW);
            glBindBufferRange(GL_UNIFORM_BUFFER, 2, uniform, alignment, 64);
            glBindBuffer(GL_UNIFORM_BUFFER, generic);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture0);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, texture1);
            glActiveTexture(GL_TEXTURE5);
            glUseProgram(before);
            glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST);
            glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ZERO, GL_ONE);
            assertThrows(IllegalStateException.class, () -> {
                try (HudGlState ignored = HudGlState.capture(2, List.of(2))) {
                    glUseProgram(during);
                    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, scratch);
                    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, scratch);
                    glBindBuffer(GL_UNIFORM_BUFFER, replacement);
                    glBufferData(GL_UNIFORM_BUFFER, 64, GL_DYNAMIC_DRAW);
                    glBindBufferBase(GL_UNIFORM_BUFFER, 2, replacement);
                    glEnable(GL_BLEND); glDisable(GL_DEPTH_TEST);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
                    throw new IllegalStateException("simulated draw failure");
                }
            });
            assertEquals(before, glGetInteger(GL_CURRENT_PROGRAM));
            assertEquals(GL_TEXTURE5, glGetInteger(GL_ACTIVE_TEXTURE));
            glActiveTexture(GL_TEXTURE0); assertEquals(texture0, glGetInteger(GL_TEXTURE_BINDING_2D));
            glActiveTexture(GL_TEXTURE1); assertEquals(texture1, glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(uniform, glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 2));
            assertEquals(alignment, glGetInteger64i(GL_UNIFORM_BUFFER_START, 2));
            assertEquals(64, glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 2));
            assertEquals(generic, glGetInteger(GL_UNIFORM_BUFFER_BINDING));
            assertFalse(glIsEnabled(GL_BLEND)); assertTrue(glIsEnabled(GL_DEPTH_TEST));
            assertEquals(GL_ONE, glGetInteger(GL_BLEND_SRC_RGB));
            assertEquals(GL_ZERO, glGetInteger(GL_BLEND_DST_RGB));
            assertEquals(GL_ZERO, glGetInteger(GL_BLEND_SRC_ALPHA));
            assertEquals(GL_ONE, glGetInteger(GL_BLEND_DST_ALPHA));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glUseProgram(0); glDeleteProgram(before); glDeleteProgram(during);
            glDeleteTextures(texture0); glDeleteTextures(texture1); glDeleteTextures(scratch);
            glDeleteBuffers(uniform); glDeleteBuffers(generic); glDeleteBuffers(replacement);
        }
    }

    @Test void initiallyUnboundUniformSlotsAndTextureUnitsRemainUnbound() {
        int buffer = glGenBuffers(), texture = glGenTextures();
        try {
            try (HudGlState ignored = HudGlState.capture(1, List.of(3))) {
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
                glBindBuffer(GL_UNIFORM_BUFFER, buffer);
                glBufferData(GL_UNIFORM_BUFFER, 64, GL_DYNAMIC_DRAW);
                glBindBufferBase(GL_UNIFORM_BUFFER, 3, buffer);
            }
            assertEquals(0, glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(0, glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 3));
            assertEquals(0, glGetInteger(GL_UNIFORM_BUFFER_BINDING));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glDeleteBuffers(buffer); glDeleteTextures(texture); }
    }

    @Test void nativeImageBridgePreservesRedBlueAndRejectsClosedAllocations() {
        var image = new com.mojang.blaze3d.platform.NativeImage(2, 1, false);
        try {
            image.setPixelRGBA(0, 0, 0xff0000ff);
            image.setPixelRGBA(1, 0, 0xffee3300);
            assertEquals(0xffff0000, indi.mopelotus.musichud.client.utils.image.NativeImageAccess.argb(image.getPixelRGBA(0, 0)));
            assertEquals(0xff0033ee, indi.mopelotus.musichud.client.utils.image.NativeImageAccess.argb(image.getPixelRGBA(1, 0)));
            assertNotEquals(0, indi.mopelotus.musichud.client.utils.image.NativeImageAccess.pixels(image));
        } finally { image.close(); }
        assertThrows(IllegalStateException.class, () -> indi.mopelotus.musichud.client.utils.image.NativeImageAccess.pixels(image));
    }

    @Test void resourceReloadDeletesOldProgramsAndCreatesAFreshGeneration() {
        try (var cache = new HudShaderProgramCache()) {
            var first = cache.get("background", () -> new HudShaderProgram(program()));
            int oldId = first.getProgramId();
            assertSame(first, cache.get("background", () -> { throw new AssertionError("unexpected recompile"); }));
            cache.invalidate();
            assertEquals(0, first.getProgramId());
            assertFalse(glIsProgram(oldId));
            var next = cache.get("background", () -> new HudShaderProgram(program()));
            assertNotSame(first, next);
            assertTrue(glIsProgram(next.getProgramId()));
        }
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test void failedShadersCanRecoverOnReloadButShutdownRejectsLateWork() {
        var cache = new HudShaderProgramCache();
        var failed = cache.get("album", () -> new HudShaderProgram(0));
        assertSame(failed, cache.get("album", () -> { throw new AssertionError("repeat failure"); }));
        cache.invalidate();
        var recovered = cache.get("album", () -> new HudShaderProgram(program()));
        int id = recovered.getProgramId();
        cache.close();
        cache.invalidate();
        assertFalse(glIsProgram(id));
        assertThrows(IllegalStateException.class, () -> cache.get("album", () -> { throw new AssertionError("late allocation"); }));
        cache.close();
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test void uniformHandleDeletionIsIdempotentAndPreservesOtherBuffers() {
        int unrelated = glGenBuffers();
        try {
            glBindBuffer(GL_UNIFORM_BUFFER, unrelated);
            glBufferData(GL_UNIFORM_BUFFER, 16, GL_DYNAMIC_DRAW);
            var data = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
            var owned = HudShaderProgram.UniformBufferHandle.createAndUpload(2, data);
            int id = owned.getUboId();
            assertTrue(glIsBuffer(id));
            owned.delete(); owned.delete();
            assertEquals(0, owned.getUboId());
            assertFalse(glIsBuffer(id));
            assertTrue(glIsBuffer(unrelated));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glDeleteBuffers(unrelated); }
    }

    private static int program() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vertex, "#version 150\nin vec3 Position; void main(){gl_Position=vec4(Position,1);}");
            glShaderSource(fragment, "#version 150\nout vec4 color; void main(){color=vec4(1);}");
            glCompileShader(vertex); glCompileShader(fragment);
            assertEquals(GL_TRUE, glGetShaderi(vertex, GL_COMPILE_STATUS), glGetShaderInfoLog(vertex));
            assertEquals(GL_TRUE, glGetShaderi(fragment, GL_COMPILE_STATUS), glGetShaderInfoLog(fragment));
            glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
            assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
            return program;
        } finally { glDeleteShader(vertex); glDeleteShader(fragment); }
    }
}
