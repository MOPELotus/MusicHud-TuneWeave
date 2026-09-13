package indi.mopelotus.musichud.client.ui.hud;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Reads the actual Minecraft dependency without booting Minecraft or an OpenGL context. */
class HudFrameInjectionTest {
    private static final String HUD = "indi/mopelotus/musichud/client/ui/hud/HudRendererManager";
    private static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";

    @Test
    void initialResourceLoadingReturnsBeforeHudOrModernUiShadersAreUsed() throws IOException {
        MethodNode entry = frameInjection().handler();
        MethodInsnNode ready = calls(entry).stream().filter(call -> call.owner.equals("net/minecraft/client/Minecraft")
                && call.name.equals("isGameLoadFinished") && call.desc.equals("()Z")).findFirst().orElseThrow();
        assertTrue(readClass(ready.owner).methods.stream().anyMatch(method -> method.name.equals(ready.name)
                && method.desc.equals(ready.desc) && (method.access & Opcodes.ACC_PUBLIC) != 0));
        AbstractInsnNode next = ready.getNext();
        while (next.getOpcode() < 0) next = next.getNext();
        JumpInsnNode branch = assertInstanceOf(JumpInsnNode.class, next);
        assertEquals(Opcodes.IFNE, branch.getOpcode(), "Only loaded resources may reach HUD submission");
        next = branch.getNext();
        while (next.getOpcode() < 0) next = next.getNext();
        assertEquals(Opcodes.RETURN, next.getOpcode());
        MethodInsnNode render = calls(entry).stream().filter(HudFrameInjectionTest::isHudExtraction).findFirst().orElseThrow();
        assertTrue(entry.instructions.indexOf(branch.label) < entry.instructions.indexOf(render));
    }

    @Test
    void skippedFramesReturnBeforeGuiConstructionAndHudInjection() throws Exception {
        MethodNode target = frameInjection().target();
        int firstReturn = instructions(target).filter(i -> i.getOpcode() == Opcodes.RETURN)
                .mapToInt(target.instructions::indexOf).min().orElseThrow();
        MethodInsnNode graphics = calls(target).stream().filter(call -> call.owner.equals(GRAPHICS)
                && call.name.equals("<init>")).findFirst().orElseThrow();
        assertTrue(firstReturn < target.instructions.indexOf(graphics));
        assertTrue(target.instructions.indexOf(graphics) < injectionIndex(frameInjection()));
    }

    @Test
    void callbackAndCapturedLocalsMatchTheActualMinecraftDrawBoundary() throws IOException {
        Injection injection = frameInjection();
        MethodNode target = injection.target();
        AnnotationNode at = (AnnotationNode) ((List<?>) value(injection.annotation(), "at")).getFirst();
        assertEquals("INVOKE", value(at, "value"));
        int point = injectionIndex(injection);
        List<Type> arguments = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(target.desc)));
        arguments.add(Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"));
        arguments.add(Type.getObjectType(GRAPHICS));
        assertEquals(arguments, Arrays.asList(Type.getArgumentTypes(injection.handler().desc)));
        assertEquals(1, target.localVariables.stream()
                .filter(local -> local.desc.equals("L" + GRAPHICS + ";"))
                .filter(local -> target.instructions.indexOf(local.start) <= point
                        && point < target.instructions.indexOf(local.end)).count(),
                "The frame must expose exactly one live GuiGraphics at the draw boundary");
        List<AnnotationNode> locals = injection.handler().visibleParameterAnnotations != null
                ? injection.handler().visibleParameterAnnotations[3]
                : injection.handler().invisibleParameterAnnotations[3];
        assertTrue(locals.stream().anyMatch(annotation -> annotation.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")),
                "Capture only GuiGraphics; other mods may eliminate unused frame locals");
        assertEquals(Type.VOID_TYPE, Type.getReturnType(injection.handler().desc));
    }

    @Test
    void uniformBindingCallbackMatchesTheActualDrawRange() throws IOException {
        ClassNode mixin = readClass("indi/mopelotus/musichud/mixin/GuiRendererHudMixin");
        MethodNode handler = mixin.methods.stream().filter(method -> method.name.equals("onExecuteDrawRange"))
                .findFirst().orElseThrow();
        MethodNode target = readClass("net/minecraft/client/gui/render/GuiRenderer").methods.stream()
                .filter(method -> method.name.equals("executeDrawRange")).findFirst().orElseThrow();
        List<Type> expected = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(target.desc)));
        expected.add(Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfo"));
        expected.add(Type.getObjectType("com/mojang/blaze3d/systems/RenderPass"));
        assertEquals(expected, Arrays.asList(Type.getArgumentTypes(handler.desc)));
        assertEquals(1, calls(target).stream().filter(call -> call.name.equals("bindDefaultUniforms")
                && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")).count());
    }

    @Test
    void fullFrameExtractsWorldMenusLoadingScreensAndOverlaysBeforeHud() throws IOException {
        MethodNode target = frameInjection().target();
        List<MethodInsnNode> calls = calls(target);
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("render")
                && call.desc.equals("(L" + GRAPHICS + ";Lnet/minecraft/client/DeltaTracker;)V")),
                "The selected method must cover the in-world HUD");
        MethodInsnNode overlay = calls.stream().filter(call -> call.owner.equals("net/minecraft/client/gui/screens/Overlay")
                && call.name.equals("render")).findFirst().orElseThrow();
        MethodInsnNode screen = calls.stream().filter(call -> call.owner.equals(SCREEN)
                && call.name.equals("renderWithTooltipAndSubtitles")).findFirst().orElseThrow();
        MethodInsnNode toasts = calls.stream().filter(call -> call.owner.equals("net/minecraft/client/gui/components/toasts/ToastManager")
                && call.name.equals("render")).findFirst().orElseThrow();
        int returnIndex = injectionIndex(frameInjection());
        for (MethodInsnNode call : List.of(overlay, screen, toasts)) {
            assertTrue(target.instructions.indexOf(call) < returnIndex,
                    "The normal HUD must be extracted after screen and overlay content");
        }

        MethodNode wrapper = readClass(SCREEN).methods.stream()
                .filter(method -> method.name.equals(screen.name) && method.desc.equals(screen.desc))
                .findFirst().orElseThrow();
        assertTrue(calls(wrapper).stream().anyMatch(call -> call.owner.equals(SCREEN)
                && call.name.equals("render") && call.getOpcode() == Opcodes.INVOKEVIRTUAL),
                "Screen subclasses must be dispatched through the full-frame path");
        for (String name : List.of("TitleScreen", "PauseScreen", "LevelLoadingScreen", "multiplayer/ServerReconfigScreen")) {
            assertScreenSubclass("net/minecraft/client/gui/screens/" + name);
        }
        assertScreenSubclass("indi/mopelotus/musichud/client/ui/screen/HudLayoutEditorScreen");
    }

    @Test
    void configuredMixinsProvideOneNormalHudExtractionInANewStratum() throws IOException {
        List<MethodNode> entrypoints = new ArrayList<>();
        try (var input = getClass().getResourceAsStream("/musichud_tuneweave.mixins.json")) {
            assertNotNull(input);
            var config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var name : config.getAsJsonArray("client")) {
                for (MethodNode method : readClass(prefix + name.getAsString()).methods) {
                    if (calls(method).stream().anyMatch(HudFrameInjectionTest::isHudExtraction)) {
                        entrypoints.add(method);
                    }
                }
            }
        }
        assertEquals(1, entrypoints.size(), "Additional screen mixins would advance HUD state twice per frame");
        MethodNode entry = entrypoints.getFirst();
        MethodNode fullFrameHandler = frameInjection().handler();
        assertEquals(fullFrameHandler.name + fullFrameHandler.desc, entry.name + entry.desc,
                "The configured entry must be the full-frame injection verified against Minecraft");
        List<MethodInsnNode> calls = calls(entry);
        assertEquals(1, calls.stream().filter(HudFrameInjectionTest::isHudExtraction).count());
        MethodInsnNode advance = calls.stream().filter(call -> call.owner.equals(GRAPHICS)
                && call.name.equals("nextStratum")).findFirst().orElseThrow();
        MethodInsnNode render = calls.stream().filter(HudFrameInjectionTest::isHudExtraction).findFirst().orElseThrow();
        assertTrue(entry.instructions.indexOf(advance) < entry.instructions.indexOf(render));
    }

    private static int injectionIndex(Injection injection) {
        AnnotationNode at = (AnnotationNode) ((List<?>) value(injection.annotation(), "at")).getFirst();
        String selector = (String) value(at, "target");
        List<MethodInsnNode> matching = calls(injection.target()).stream()
                .filter(call -> selector.equals("L" + call.owner + ";" + call.name + call.desc)).toList();
        assertEquals(1, matching.size(), "Each rendered frame must submit HUD once before the GPU draw");
        return injection.target().instructions.indexOf(matching.getFirst());
    }

    private static Injection frameInjection() throws IOException {
        ClassNode mixin = readClass("indi/mopelotus/musichud/mixin/GuiFrameHudMixin");
        AnnotationNode targetAnnotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        Type owner = (Type) ((List<?>) value(targetAnnotation, "value")).getFirst();
        MethodNode handler = mixin.methods.stream().filter(method -> calls(method).stream()
                .anyMatch(HudFrameInjectionTest::isHudExtraction)).findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, handler.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        List<?> selectors = (List<?>) value(inject, "method");
        assertEquals(1, selectors.size());
        String selector = (String) selectors.getFirst();
        List<MethodNode> targets = readClass(owner.getInternalName()).methods.stream()
                .filter(method -> selector.equals(method.name) || selector.equals(method.name + method.desc)).toList();
        assertEquals(1, targets.size(), "The full-frame injection must resolve to one actual Minecraft method");
        return new Injection(handler, inject, targets.getFirst());
    }

    private static void assertScreenSubclass(String name) throws IOException {
        String current = name;
        while (!SCREEN.equals(current) && current != null) current = readClass(current).superName;
        assertEquals(SCREEN, current, name + " must be covered by the shared screen dispatch");
    }

    private static ClassNode readClass(String internalName) throws IOException {
        try (var input = HudFrameInjectionTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Missing actual class: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        return Stream.of(visible, invisible).filter(list -> list != null).flatMap(List::stream)
                .filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value: " + key);
    }

    private static Stream<AbstractInsnNode> instructions(MethodNode method) {
        return Arrays.stream(method.instructions.toArray());
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        return instructions(method).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static boolean isHudExtraction(MethodInsnNode call) {
        return call.owner.equals(HUD) && call.name.equals("renderFrame");
    }

    private record Injection(MethodNode handler, AnnotationNode annotation, MethodNode target) {}

}
