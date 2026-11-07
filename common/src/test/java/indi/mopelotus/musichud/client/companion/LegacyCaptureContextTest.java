package indi.mopelotus.musichud.client.companion;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCaptureContextTest {
    @Test void vulkanNeverEntersOpenGlEvenWhenExportWasSavedAsEnabled() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var fixture = fixture();
            fixture.getField("active").setBoolean(null, enabled);
            fixture.getMethod("render").invoke(null);
            assertFalse(fixture.getField("polled").getBoolean(null), "Vulkan must not call native GL polling");
            assertFalse(fixture.getField("active").getBoolean(null), "disable only the current export session");
        }
    }

    @Test void openGlStillPollsItsPendingReadbacks() throws Exception {
        var fixture = fixture();
        fixture.getField("context").setLong(null, 123L);
        fixture.getField("active").setBoolean(null, true);
        fixture.getMethod("render").invoke(null);
        assertTrue(fixture.getField("polled").getBoolean(null));
        assertTrue(fixture.getField("active").getBoolean(null));
    }

    // Execute the production render entry through its first GL poll. The rest is
    // deliberately cut off: this regression tests dispatch before any native GL call.
    private static Class<?> fixture() throws Exception {
        Hooks.stopped = Hooks.active = Hooks.polled = false;
        Hooks.context = 0;
        var node = new ClassNode();
        try (var stream = LegacyCaptureContextTest.class.getResourceAsStream("/indi/mopelotus/musichud/client/companion/CompanionBridge.class")) {
            new ClassReader(stream).accept(node, 0);
        }
        String name = node.name + "ContextFixture";
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var mapped = new ClassRemapper(writer, new SimpleRemapper(Map.of(node.name, name,
                "org/lwjgl/glfw/GLFW", Type.getInternalName(Hooks.class))));
        mapped.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, node.name, null, Type.getInternalName(Hooks.class), null);
        var render = node.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        boolean found = false;
        for (var instruction : render.instructions.toArray()) {
            if (found) render.instructions.remove(instruction);
            else if (instruction instanceof MethodInsnNode call && call.name.equals("poll")) {
                found = true;
                render.instructions.insert(instruction, new InsnNode(Opcodes.RETURN));
            }
        }
        assertTrue(found, "fixture must reach the real production poll boundary");
        render.tryCatchBlocks.clear();
        render.localVariables = null;
        render.accept(mapped);
        mapped.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(LegacyCaptureContextTest.class.getClassLoader()); }
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().define();
    }

    public static class Hooks {
        public static boolean stopped, active, polled;
        public static long context;
        public static void restorePreferencesOnce() {}
        public static long glfwGetCurrentContext() { return context; }
        public static boolean enabled() { return active; }
        public static void setRuntimeEnabled(boolean enabled) { active = enabled; }
        public static void poll() { polled = true; }
    }
}
