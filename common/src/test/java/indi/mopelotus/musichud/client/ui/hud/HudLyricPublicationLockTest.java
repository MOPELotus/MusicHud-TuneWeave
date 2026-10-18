package indi.mopelotus.musichud.client.ui.hud;

import indi.mopelotus.musichud.client.ui.TimedUiQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual publication method (including monitor flags) with CPU-only dependencies. */
class HudLyricPublicationLockTest {
    private static final String ROOT = "indi.mopelotus.musichud.";
    private static final String MANAGER = ROOT + "client.ui.hud.HudRendererManager";
    @TempDir static Path fixtures;

    @BeforeAll static void preparePublicationMethod() throws Exception {
        Map<String, String> sources = Map.of(
                MANAGER, """
                public class HudRendererManager {
                    public final indi.mopelotus.musichud.client.ui.hud.renderer.ScrollingLyricLineRenderer LYRICS_LINE_RENDERER = new indi.mopelotus.musichud.client.ui.hud.renderer.ScrollingLyricLineRenderer();
                    public final indi.mopelotus.musichud.client.audio.NowPlayingInfo nowPlayingInfo = new indi.mopelotus.musichud.client.audio.NowPlayingInfo();
                    public volatile boolean closed;
                    public record LyricStyles(indi.mopelotus.musichud.client.ui.hud.renderer.ScrollingLyricLineRenderer.Line first,
                        indi.mopelotus.musichud.client.ui.hud.renderer.ScrollingLyricLineRenderer.Line second,
                        indi.mopelotus.musichud.client.audio.NowPlayingInfo.PlaybackSnapshot source) {}
                    private void publishLyricStyles(LyricStyles lines) { throw new AssertionError("Production method not installed"); }
                }
                """,
                ROOT + "client.ui.hud.renderer.ScrollingLyricLineRenderer", """
                public class ScrollingLyricLineRenderer {
                    public int publications;
                    public record Line() {}
                    public synchronized void setLines(Line first, Line second) { publications++; }
                    public synchronized void hardClear() { publications = 0; }
                }
                """,
                ROOT + "client.audio.NowPlayingInfo", """
                public class NowPlayingInfo {
                    public volatile PlaybackSnapshot current = new PlaybackSnapshot(null, null);
                    public PlaybackSnapshot snapshot() { return current; }
                    public record PlaybackSnapshot(indi.mopelotus.musichud.beans.music.MusicDetail musicDetail,
                        java.time.ZonedDateTime startedAt) {}
                }
                """);
        // Gradle's worker classpath omits the test runtime loader's module output paths.
        Class<?> musicDetail = Class.forName(ROOT + "beans.music.MusicDetail", false,
                HudLyricPublicationLockTest.class.getClassLoader());
        String classpath = System.getProperty("java.class.path") + File.pathSeparator
                + Path.of(musicDetail.getProtectionDomain().getCodeSource().getLocation().toURI());
        var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classpath, "-d", fixtures.toString()));
        for (var source : sources.entrySet()) {
            Path file = fixtures.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + source.getKey().substring(0, source.getKey().lastIndexOf('.')) + ";\n" + source.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        ClassNode production = new ClassNode();
        try (var input = HudLyricPublicationLockTest.class.getClassLoader().getResourceAsStream(MANAGER.replace('.', '/') + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(production, 0);
        }
        var method = production.methods.stream().filter(m -> m.name.equals("publishLyricStyles")).findFirst().orElseThrow();
        Path shellFile = fixtures.resolve(MANAGER.replace('.', '/') + ".class");
        ClassNode shell = new ClassNode();
        new ClassReader(Files.readAllBytes(shellFile)).accept(shell, 0);
        shell.methods.removeIf(m -> m.name.equals(method.name));
        shell.methods.add(method); // Preserve real instructions, exception table, and ACC_SYNCHRONIZED.
        var writer = new ClassWriter(0);
        shell.accept(writer);
        Files.write(shellFile, writer.toByteArray());
    }

    @Test void publicationCompletesWhilePlaybackOwnerHoldsManagerAndCancelsQueue() throws Exception {
        try (Scene scene = new Scene()) {
            var timer = new AtomicReference<Runnable>();
            var callbackEntered = new CountDownLatch(1);
            var queue = new TimedUiQueue<Object>(() -> 0L, (delay, task) -> timer.set(task), Runnable::run,
                    value -> { callbackEntered.countDown(); scene.publish(value); }, 2);
            ExecutorService workers = workers();
            try {
                Future<?> owner = workers.submit(() -> {
                    synchronized (scene.manager) {
                        queue.add(scene.styles, 0);
                        Future<?> callback = workers.submit(timer.get());
                        await(callbackEntered);
                        queue.cancel(); // Same manager -> queue order used by switch/reset/shutdown.
                        get(callback);
                    }
                });
                owner.get(3, TimeUnit.SECONDS);
                assertEquals(1, scene.publications());
            } finally { workers.shutdownNow(); }
        }
    }

    @Test void closedGuardIsCheckedAfterAcquiringRendererBoundary() throws Exception {
        try (Scene scene = new Scene()) {
            runBlockedPublication(scene, () -> {
                try { scene.manager.getClass().getField("closed").setBoolean(scene.manager, true); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertEquals(0, scene.publications());
        }
    }

    @Test void changedPlaybackSnapshotRejectsAnAlreadyWaitingPublication() throws Exception {
        try (Scene scene = new Scene()) {
            runBlockedPublication(scene, () -> {
                try {
                    Class<?> snapshot = scene.info.getClass().getField("current").getType();
                    Object changed = snapshot.getConstructors()[0].newInstance(null, ZonedDateTime.now());
                    scene.info.getClass().getField("current").set(scene.info, changed);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertEquals(0, scene.publications());
        }
    }

    private static void runBlockedPublication(Scene scene, Runnable invalidate) throws Exception {
        ExecutorService workers = workers();
        try {
            var entered = new CountDownLatch(1);
            Future<?> callback;
            var publisher = new AtomicReference<Thread>();
            synchronized (scene.renderer) {
                callback = workers.submit(() -> {
                    publisher.set(Thread.currentThread());
                    entered.countDown();
                    scene.publish(scene.styles);
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                awaitRendererBoundary(publisher.get(), scene.renderer);
                invalidate.run();
                scene.renderer.getClass().getMethod("hardClear").invoke(scene.renderer);
            }
            callback.get(3, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    private static void awaitRendererBoundary(Thread publisher, Object renderer) {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() - deadline < 0) {
            var state = threads.getThreadInfo(publisher.threadId());
            if (state != null && state.getThreadState() == Thread.State.BLOCKED
                    && state.getLockInfo() != null
                    && state.getLockInfo().getIdentityHashCode() == System.identityHashCode(renderer)) return;
            Thread.onSpinWait();
        }
        fail("Publication never reached the renderer monitor");
    }

    private static ExecutorService workers() {
        return Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void get(Future<?> future) {
        try { future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static final class Scene extends URLClassLoader {
        final Object manager, renderer, info, styles;
        Scene() throws Exception {
            super(new URL[]{fixtures.toUri().toURL()}, HudLyricPublicationLockTest.class.getClassLoader());
            manager = loadClass(MANAGER).getConstructor().newInstance();
            renderer = manager.getClass().getField("LYRICS_LINE_RENDERER").get(manager);
            info = manager.getClass().getField("nowPlayingInfo").get(manager);
            styles = loadClass(MANAGER + "$LyricStyles").getConstructors()[0]
                    .newInstance(null, null, info.getClass().getMethod("snapshot").invoke(info));
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> type = findLoadedClass(name);
            if (type == null) type = Files.exists(fixtures.resolve(name.replace('.', '/') + ".class"))
                    ? findClass(name) : super.loadClass(name, false);
            if (resolve) resolveClass(type);
            return type;
        }
        void publish(Object value) {
            try {
                var method = manager.getClass().getDeclaredMethod("publishLyricStyles", styles.getClass());
                method.setAccessible(true);
                method.invoke(manager, value);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        int publications() throws Exception { return renderer.getClass().getField("publications").getInt(renderer); }
    }
}
