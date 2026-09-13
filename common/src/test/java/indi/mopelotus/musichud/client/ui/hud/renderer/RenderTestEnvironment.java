package indi.mopelotus.musichud.client.ui.hud.renderer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Only environment dependencies are replaced; the renderer and transform methods remain production bytecode. */
final class RenderTestEnvironment {
    private static final String ROOT = "indi.mopelotus.musichud.";
    private static final String RENDER = ROOT + "client.ui.hud.renderer.";

    static void compile(Path directory) throws Exception {
        var node = new ClassNode();
        try (var input = RenderTestEnvironment.class.getResourceAsStream("HudRenderContext$Transforming.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        boolean legacy = node.fields.stream().anyMatch(f -> f.desc.contains("PoseStack"));
        String graphicsName = node.methods.stream().filter(m -> m.name.equals("<init>")
                        && m.desc.contains("net/minecraft/client/gui/"))
                .findFirst().orElseThrow().desc.split("gui/")[1].split(";")[0];
        String poseType = legacy ? "com.mojang.blaze3d.vertex.PoseStack" : "org.joml.Matrix3x2fStack";
        String push = legacy ? "pushPose" : "pushMatrix", pop = legacy ? "popPose" : "popMatrix";
        String translate = legacy ? "translate(7, 11, 0)" : "translate(7, 11)";
        String matrix = legacy ? "pose.last().pose()" : "pose";
        String matrixType = legacy ? "org.joml.Matrix4f" : "org.joml.Matrix3x2f";
        String graphics = "net.minecraft.client.gui." + graphicsName;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(graphics, """
                public class %s {
                    public final %s pose = new %s(%s);
                    public %s pose() { return pose; }
                }
                """.formatted(graphicsName, poseType, poseType, legacy ? "" : "32", poseType));
        sources.put(RENDER + "HudRenderContext", """
                import java.util.*;
                import java.util.function.Consumer;
                import net.minecraft.client.gui.Font;
                import %s;
                public class HudRenderContext {
                    public final %s graphics = new %s();
                    private final Deque<String> clips = new ArrayDeque<>();
                    public RuntimeException failure;
                    public int failAtDraw, draws, clipDepthAtFailure;
                    public boolean failTransform;
                    public void seedCallerState() {
                        clips.push("caller");
                        graphics.pose().%s(); graphics.pose().%s;
                    }
                    public void assertCallerState() {
                        if (clips.size() != 1 || !"caller".equals(clips.peek()))
                            throw new AssertionError("Leaked or removed caller scissor: " + clips);
                        var pose = graphics.pose();
                        var expected = new %s().translate(7, 11%s);
                        if (!%s.equals(expected)) throw new AssertionError("Caller transform changed");
                        pose.%s();
                        if (!%s.equals(new %s())) throw new AssertionError("Leaked nested pose");
                        pose.%s(); pose.%s;
                    }
                    public Transforming transform() {
                        if (failTransform) throw failure;
                        return new Transforming(graphics);
                    }
                    public void pushScissor(int x, int y, int x2, int y2) { clips.push(x+","+y+","+x2+","+y2); }
                    public void popScissor() { clips.pop(); }
                    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
                        if (++draws == failAtDraw) { clipDepthAtFailure = clips.size(); throw failure; }
                    }
                    // Compile-time shell only. The classloader always supplies the actual compiled nested class.
                    public static class Transforming {
                        private Transforming(%s graphics) { throw new AssertionError("Production transform not loaded"); }
                    }
                }
                """.formatted(graphics, graphicsName, graphicsName, push, translate, matrixType,
                legacy ? ", 0" : "", matrix, pop, matrix, matrixType, push, translate, graphicsName));
        sources.put("net.minecraft.client.gui.Font", """
                public class Font {
                    public final int lineHeight = 10;
                    public int width(String text) { return text.length() * 10; }
                }
                """);
        sources.put("net.minecraft.client.Minecraft", """
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public final net.minecraft.client.gui.Font font = new net.minecraft.client.gui.Font();
                    public static Minecraft getInstance() { return INSTANCE; }
                }
                """);
        sources.put("net.minecraft.network.chat.Style", "public class Style { public static final Style EMPTY = new Style(); }");
        sources.put("icyllis.modernui.mc.FontResourceManager", "public class FontResourceManager { public static FontResourceManager getInstance() { return icyllis.modernui.mc.text.TextLayoutEngine.getInstance(); } }");
        sources.put("icyllis.modernui.mc.text.TextLayoutEngine", """
                public class TextLayoutEngine extends icyllis.modernui.mc.FontResourceManager {
                    private static final TextLayoutEngine INSTANCE = new TextLayoutEngine();
                    public static TextLayoutEngine getInstance() { return INSTANCE; }
                    public ModernStringSplitter getStringSplitter() { return new ModernStringSplitter(); }
                }
                """);
        sources.put("icyllis.modernui.mc.text.ModernStringSplitter", """
                public class ModernStringSplitter {
                    public float measureText(String text) { return text.length() * 10f; }
                    public float stringWidth(String text) { return measureText(text); }
                    public int indexByWidth(String text, float width, net.minecraft.network.chat.Style style) {
                        return Math.min(text.length(), (int)(width / 10));
                    }
                }
                """);
        sources.put(ROOT + "MusicHud", "public class MusicHud { public static org.apache.logging.log4j.Logger getLogger(Class<?> type) { return org.apache.logging.log4j.LogManager.getLogger(type); } }");
        sources.put(ROOT + "interfaces.ClientConfig", """
                public interface ClientConfig {
                    static ClientConfig getInstance() { return new ClientConfig() {}; }
                    default boolean getShowTranslatedCnLyrics() { return true; }
                    default boolean getEnableMarqueeText() { return true; }
                }
                """);
        sources.put(ROOT + "client.utils.ui.Easing", """
                public enum Easing {
                    EASE_IN_OUT_SINE, EASE_IN_OUT_QUINT;
                    public float getInterpolation(float value) { return value; }
                }
                """);
        sources.put(ROOT + "client.ui.hud.metadata.Layout", """
                public class Layout {
                    public float getWidth() { return 50; }
                    public float getHeight() { return 20; }
                    public AbsolutePosition calcAbsolutePosition(indi.mopelotus.musichud.client.ui.hud.renderer.HudRenderContext context) {
                        return new AbsolutePosition(3, 5);
                    }
                    public record AbsolutePosition(float x, float y) {}
                }
                """);
        sources.put(ROOT + "client.audio.NowPlayingInfo", """
                public class NowPlayingInfo {
                    public static NowPlayingInfo getInstance() { return new NowPlayingInfo(); }
                    public java.time.Duration getPlayedDuration() { return java.time.Duration.ofMillis(500); }
                }
                """);
        sources.put(ROOT + "client.ui.dto.LyricLine", """
                import java.time.Duration;
                import java.util.List;
                public class LyricLine {
                    private final boolean word;
                    public LyricLine(boolean word) { this.word = word; }
                    public boolean isWordByWord() { return word; }
                    public void parsePhrases() {}
                    public Duration getStartTime() { return Duration.ZERO; }
                    public int binarySearchPhraseIndex(Duration duration) { return 0; }
                    public List<Phrase> getPhrases() { return List.of(new Phrase()); }
                    public static class Phrase {
                        public int endOffset() { return 3; }
                        public Duration endTime() { return Duration.ofSeconds(1); }
                        public int durationMillis() { return 1000; }
                    }
                }
                """);
        String classpath = System.getProperty("java.class.path");
        for (String name : new String[]{poseType, "org.joml.Matrix4f", "org.apache.logging.log4j.Logger"}) {
            Class<?> type = Class.forName(name, false, RenderTestEnvironment.class.getClassLoader());
            classpath += File.pathSeparator + Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classpath, "-d", directory.toString()));
        for (var entry : sources.entrySet()) {
            String name = entry.getKey();
            Path source = directory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + entry.getValue());
            arguments.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)),
                () -> "Compile isolated rendering environment: " + diagnostics);
    }

}
