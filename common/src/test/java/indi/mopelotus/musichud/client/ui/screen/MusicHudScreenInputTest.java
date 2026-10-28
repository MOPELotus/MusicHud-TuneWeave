package indi.mopelotus.musichud.client.ui.screen;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production input method with only Minecraft and the UI host replaced. */
class MusicHudScreenInputTest {
    @Test void supplementaryCharactersReachHostAsOneCodePoint() throws Exception {
        Host host = new Host();
        Object screen = screen(host);
        var input = screen.getClass().getMethod("charTyped", Input.class);
        for (int codePoint : new int[] {'A', 0x1F3B5, 0x20000, 0x10FFFF}) {
            host.codePoints.clear();
            assertEquals(true, input.invoke(screen, new Input(codePoint)));
            assertEquals(List.of(codePoint), host.codePoints,
                    "one Unicode event must remain one code point at the Modern UI boundary");
        }
        host.handled = false;
        assertEquals(false, input.invoke(screen, new Input(0x1F3B5)));
    }

    @Test void focusedListenerConsumesSupplementaryInputBeforeHost() throws Exception {
        Host host = new Host();
        Object screen = screen(host);
        List<Integer> focused = new ArrayList<>();
        ((Screen) screen).focused = event -> { focused.add(event.codepoint()); return true; };
        assertEquals(true, screen.getClass().getMethod("charTyped", Input.class)
                .invoke(screen, new Input(0x1F3B5)));
        assertEquals(List.of(0x1F3B5), focused);
        assertTrue(host.codePoints.isEmpty());
    }

    public record Input(int codepoint) {}
    public interface Focused { boolean charTyped(Input event); }
    public static class Screen {
        public Focused focused;
        public Focused getFocused() { return focused; }
    }
    public static class Host {
        final List<Integer> codePoints = new ArrayList<>();
        boolean handled = true;
        public boolean onCharTyped(int codePoint) { codePoints.add(codePoint); return handled; }
    }

    private static Object screen(Host host) throws Exception {
        String original = "indi/mopelotus/musichud/client/ui/screen/MusicHudScreen";
        String target = original + "InputFixture";
        ClassNode source = new ClassNode();
        try (var bytes = MusicHudScreenInputTest.class.getResourceAsStream("MusicHudScreen.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(source, 0);
        }
        var method = source.methods.stream().filter(m -> m.name.equals("charTyped")).findFirst().orElseThrow();
        String event = Type.getArgumentTypes(method.desc)[0].getInternalName();
        Map<String, String> names = new HashMap<>();
        names.put(original, target);
        names.put(source.superName, Type.getInternalName(Screen.class));
        names.put("net/minecraft/client/gui/components/events/GuiEventListener", Type.getInternalName(Focused.class));
        names.put("icyllis/modernui/mc/UIManager", Type.getInternalName(Host.class));
        names.put(event, Type.getInternalName(Input.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var mapped = new ClassRemapper(writer, new SimpleRemapper(names));
        mapped.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, original, null, source.superName, null);
        mapped.visitField(Opcodes.ACC_PRIVATE, "mHost", "Licyllis/modernui/mc/UIManager;", null, null).visitEnd();
        var init = mapped.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Licyllis/modernui/mc/UIManager;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, source.superName, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, original, "mHost", "Licyllis/modernui/mc/UIManager;");
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        method.accept(mapped);
        mapped.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(MusicHudScreenInputTest.class.getClassLoader()); }
            Class<?> load() { byte[] bytes = writer.toByteArray(); return defineClass(target.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().load().getConstructor(Host.class).newInstance(host);
    }
}
