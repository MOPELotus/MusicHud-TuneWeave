package indi.mopelotus.musichud.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the compiled production lifecycle gates, with a queue boundary that must never receive late work.
 * This fixture does not claim to run the Mixin engine; runtime exports verify the actual injection separately. */
class ModernUiGraphicsLifecycleTest {
    @Test void initialAndRunningHoverCallbacksReachTheLiveQueue() throws Exception {
        var host = new Host();
        var delivered = new AtomicInteger();
        host.set("mRunning", false); // UI initialization precedes the constructor's running=true assignment.
        host.hover(delivered::incrementAndGet);
        host.set("mRunning", true);
        host.hover(delivered::incrementAndGet);
        assertEquals(2, delivered.get(), "startup must not be mistaken for shutdown");
    }

    @Test void shutdownRejectsCurrentAndLateHoverBeforeTouchingTheDeadQueue() throws Exception {
        var host = new Host();
        host.set("mRunning", true);
        host.set("mShutdownRequested", true); // The request flag closes ingress before loop termination.
        host.hover(() -> fail("shutdown hover reached the handler"));
        host.set("mRunning", false);
        for (int i = 0; i < 5; i++) host.hover(() -> fail("late callback reached the dead handler"));
    }

    @Test void stoppedDetachRetiresOnlyTheMatchingScreenWithoutPostingTransactions() throws Exception {
        var host = new Host();
        var current = new Screen();
        var old = new Screen();
        host.set("mScreen", current);
        assertTrue(host.detach(old).isCancelled());
        assertSame(current, host.get("mScreen"), "late old detach must preserve the current screen identity");
        assertTrue(host.detach(current).isCancelled());
        assertNull(host.get("mScreen"), "screen-change must not synthesize hover for the stopped screen");
        assertTrue(host.detach(current).isCancelled(), "repeat detach remains safe");
    }

    @Test void runningDetachKeepsTheNormalHostTransactionPath() throws Exception {
        var host = new Host();
        var current = new Screen();
        host.set("mRunning", true);
        host.set("mScreen", current);
        assertFalse(host.detach(current).isCancelled());
        assertSame(current, host.get("mScreen"), "the original host owns live detach cleanup");
    }

    @Test void hoverGuardTargetsOnlyTheHeadOfTheSharedHostEntryPoint() throws Exception {
        var method = source().methods.stream().filter(m -> m.name.endsWith("$skipStoppedHover")).findFirst().orElseThrow();
        var injection = method.visibleAnnotations.stream()
                .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("onHoverMove"), value(injection, "method"));
        assertEquals(true, value(injection, "cancellable"));
        var at = (AnnotationNode) ((java.util.List<?>) value(injection, "at")).getFirst();
        assertEquals("HEAD", value(at, "value"));
    }

    public interface MuiScreen {}
    public static class Screen implements MuiScreen {}

    private static final class Host {
        private final Object fixture;
        private final Class<?> type;
        private final Method hover, detach;

        Host() throws Exception {
            ClassNode source = source();
            String target = source.name + "LifecycleFixture";
            var remapper = new SimpleRemapper(Map.of(source.name, target,
                    "icyllis/modernui/mc/MuiScreen", Type.getInternalName(MuiScreen.class),
                    "net/minecraft/client/gui/screens/Screen", Type.getInternalName(Screen.class)));
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            var mapped = new ClassRemapper(writer, remapper);
            mapped.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, source.name, null, "java/lang/Object", null);
            for (var field : source.fields) {
                if (Set.of("mRunning", "mShutdownRequested", "mScreen").contains(field.name)) field.accept(mapped);
            }
            var init = mapped.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
            for (var method : source.methods) {
                if (method.name.endsWith("$skipStoppedHover") || method.name.endsWith("$skipStoppedDetach")) {
                    method.visibleAnnotations = null;
                    method.invisibleAnnotations = null;
                    method.accept(mapped);
                }
            }
            mapped.visitEnd();
            class Loader extends ClassLoader {
                Loader() { super(ModernUiGraphicsLifecycleTest.class.getClassLoader()); }
                Class<?> define() { return defineClass(target.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
            }
            type = new Loader().define();
            fixture = type.getConstructor().newInstance();
            hover = type.getDeclaredMethod("musichud_tuneweave$skipStoppedHover", CallbackInfo.class);
            detach = type.getDeclaredMethod("musichud_tuneweave$skipStoppedDetach", Screen.class, CallbackInfo.class);
            hover.setAccessible(true); detach.setAccessible(true);
        }

        void set(String name, Object value) throws Exception {
            var field = type.getDeclaredField(name); field.setAccessible(true); field.set(fixture, value);
        }
        Object get(String name) throws Exception {
            var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(fixture);
        }
        void hover(Runnable queueDelivery) throws Exception {
            var callback = new CallbackInfo("onHoverMove", true);
            hover.invoke(fixture, callback);
            if (!callback.isCancelled()) queueDelivery.run();
        }
        CallbackInfo detach(Screen target) throws Exception {
            var callback = new CallbackInfo("removed", true);
            detach.invoke(fixture, target, callback);
            return callback;
        }
    }

    private static ClassNode source() throws Exception {
        var source = new ClassNode();
        try (var bytes = ModernUiGraphicsLifecycleTest.class.getResourceAsStream("ModernUiGraphicsLifecycleMixin.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(source, 0);
        }
        return source;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
}
