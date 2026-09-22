package indi.mopelotus.musichud.client.ui.hud.renderer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Executes unmodified production renderer/Transforming bytecode with CPU-only environment fixtures. */
class RenderExceptionCleanupTest {
    private static final String ROOT = "indi.mopelotus.musichud.";
    private static final String RENDER = ROOT + "client.ui.hud.renderer.";
    @TempDir static Path fixtures;

    @BeforeAll static void compileEnvironment() throws Exception {
        RenderTestEnvironment.compile(fixtures);
    }

    @Test void textDrawFailureRestoresBothStacksAndTheNextRenderWorks() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.text(false), 1, 1); }
    }

    @Test void secondMarqueeCopyFailureRestoresBothStacks() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.text(true), 2, 1); }
    }

    @Test void lyricBaseDrawFailureRestoresOuterClipAndTransform() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.lyrics(true, false), 1, 1); }
    }

    @Test void lyricHighlightFailureRestoresNestedClipsAndTransform() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.lyrics(true, false), 2, 2); }
    }

    @Test void translatedLyricFailureRestoresTheCallerState() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.lyrics(true, false), 3, 1); }
    }

    @Test void transitionIncomingLineFailureRestoresTheCallerState() throws Exception {
        try (Scene s = new Scene()) { s.verifyDrawFailure(s.lyrics(false, true), 3, 1); }
    }

    @Test void transformCreationFailureStillReleasesTheTextClip() throws Exception {
        try (Scene s = new Scene()) {
            Object renderer = s.text(false);
            s.field("failTransform", true);
            assertSame(s.failure, assertThrows(InvocationTargetException.class,
                    () -> s.render(renderer)).getCause());
            s.assertBalanced();
        }
    }

    @Test void terminalTransformCallbackFailurePopsOnlyItsOwnPose() throws Exception {
        try (Scene s = new Scene()) {
            Object transform = s.call(s.context, "transform");
            Consumer<Object> fail = ignored -> { throw s.failure; };
            assertSame(s.failure, assertThrows(InvocationTargetException.class,
                    () -> s.call(transform, "end", Consumer.class, fail)).getCause());
            s.assertBalanced();
        }
    }

    @Test void nestedTransformFailurePreservesParentUntilExplicitEnd() throws Exception {
        try (Scene s = new Scene()) {
            Object transform = s.call(s.context, "transform");
            Consumer<Object> fail = ignored -> { throw s.failure; };
            assertSame(s.failure, assertThrows(InvocationTargetException.class,
                    () -> s.call(transform, "subTransform", Consumer.class, fail)).getCause());
            s.call(transform, "end");
            s.assertBalanced();
        }
    }

    @Test void successfulCallbacksAndRenderingPreserveCallerOwnedStacks() throws Exception {
        try (Scene s = new Scene()) {
            Object transform = s.call(s.context, "transform");
            s.call(transform, "subTransform", Consumer.class, (Consumer<Object>) ignored -> {});
            s.call(transform, "end", Consumer.class, (Consumer<Object>) ignored -> {});
            s.render(s.text(false));
            s.render(s.lyrics(true, false));
            s.assertBalanced();
            assertEquals(3, s.field("draws"));
        }
    }

    private static final class Scene extends URLClassLoader {
        final Object context;
        final RuntimeException failure = new RuntimeException("injected draw failure");

        Scene() throws Exception {
            super(new URL[]{fixtures.toUri().toURL()}, RenderExceptionCleanupTest.class.getClassLoader());
            context = loadClass(RENDER + "HudRenderContext").getConstructor().newInstance();
            field("failure", failure);
            call(context, "seedCallerState");
        }

        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                boolean production = List.of("TextRenderer", "ScrollingLyricLineRenderer", "HudRenderer",
                                "HudRenderContext$Transforming").stream()
                        .anyMatch(simple -> name.equals(RENDER + simple) || name.startsWith(RENDER + simple + "$"));
                if (production) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                } else if (Files.exists(fixtures.resolve(name.replace('.', '/') + ".class"))) {
                    result = findClass(name);
                } else result = super.loadClass(name, false);
            }
            if (resolve) resolveClass(result);
            return result;
        }

        Object text(boolean marquee) throws Exception {
            Object renderer = loadClass(RENDER + "TextRenderer").getConstructor().newInstance();
            call(renderer, "setLayout", loadClass(ROOT + "client.ui.hud.metadata.Layout"), layout());
            call(renderer, "setText", String.class, marquee ? "abcdefghij" : "a");
            Class<?> position = loadClass(RENDER + "TextRenderer$Position");
            call(renderer, "setPosition", position, position.getField("LEFT").get(null));
            call(renderer, "setVanillaLineHeight", int.class, 10);
            if (marquee) {
                // A stable midpoint guarantees the second marquee copy is visible without sleeping.
                call(renderer, "setMarqueeDuration", float.class, 1_000_000f);
                call(renderer, "setLastUpdateTime", long.class, System.currentTimeMillis() - 905_000);
            }
            return renderer;
        }

        Object lyrics(boolean wordByWord, boolean transition) throws Exception {
            Object renderer = loadClass(RENDER + "ScrollingLyricLineRenderer").getConstructor().newInstance();
            call(renderer, "setLayout", loadClass(ROOT + "client.ui.hud.metadata.Layout"), layout());
            call(renderer, "setLine1Height", float.class, 10f);
            call(renderer, "setLine2Height", float.class, 10f);
            Class<?> lyric = loadClass(ROOT + "client.ui.dto.LyricLine");
            Class<?> line = loadClass(RENDER + "ScrollingLyricLineRenderer$Line");
            var constructor = line.getConstructor(lyric, String.class, int.class, int.class, long.class);
            Object l = constructor.newInstance(lyric.getConstructor(boolean.class).newInstance(wordByWord), "abc", 1, 2, 0L);
            renderer.getClass().getMethod("setLines", line, line).invoke(renderer, l, l);
            var started = renderer.getClass().getDeclaredField("transitionStartTime");
            started.setAccessible(true);
            started.setLong(renderer, System.currentTimeMillis() - 1000);
            render(renderer); // Complete the first line transition using the production state machine.
            if (transition) {
                Object next = constructor.newInstance(lyric.getConstructor(boolean.class).newInstance(false), "next", 1, 2, 0L);
                renderer.getClass().getMethod("setLines", line, line).invoke(renderer, next, next);
                // Keep the incoming transition active throughout this exception-path test.
                started.setLong(renderer, System.currentTimeMillis() + 1_000_000);
            }
            field("draws", 0);
            return renderer;
        }

        Object layout() throws Exception {
            return loadClass(ROOT + "client.ui.hud.metadata.Layout").getConstructor().newInstance();
        }

        void verifyDrawFailure(Object renderer, int draw, int nestedClips) throws Exception {
            field("draws", 0); field("failAtDraw", draw);
            assertSame(failure, assertThrows(InvocationTargetException.class, () -> render(renderer)).getCause());
            assertEquals(1 + nestedClips, field("clipDepthAtFailure"), "Exercise the intended nested draw path");
            assertBalanced();
            field("failAtDraw", 0);
            render(renderer);
            assertBalanced();
        }

        void assertBalanced() throws Exception { call(context, "assertCallerState"); }
        void render(Object renderer) throws Exception { call(renderer, "render", context.getClass(), context); }
        Object field(String name) throws Exception { return context.getClass().getField(name).get(context); }
        void field(String name, Object value) throws Exception { context.getClass().getField(name).set(context, value); }
        Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
        Object call(Object target, String method, Class<?> type, Object arg) throws Exception {
            return target.getClass().getMethod(method, type).invoke(target, arg);
        }
    }
}
