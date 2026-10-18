package indi.mopelotus.musichud.client.ui.components;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production view bytecode with deterministic scheduling and resource-free rows. */
class StaggeredLyricLifecycleTest {
    @TempDir Path fixtures;
    private final LayoutTestContext context = new LayoutTestContext();
    private final LyricLine first = LyricLine.builder().text("first").type(LyricLine.Type.NORMAL).build();
    private final LyricLine second = LyricLine.builder().text("second").type(LyricLine.Type.NORMAL).build();

    @Test void clearRejectsSynchronousAndDelayedCancelledAnimationEnds() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            e.attached(true);
            e.switchTo(2, second);
            Object oldAnimation = e.field("switchAnimator");
            assertNotNull(oldAnimation);
            e.clear(); // The deterministic cancel invokes onAnimationEnd synchronously.
            e.assertEmpty();
            e.end(oldAnimation); // A platform may also deliver an already queued completion.
            e.assertEmpty();
            assertNull(e.field("switchAnimator"));
        }
    }

    @Test void detachedClearRejectsPostedRowWorkAndOldAnimationAfterNewTrack() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            List<Runnable> oldPosts = e.takeViewPosts();
            e.attached(true);
            e.switchTo(2, second);
            Object oldAnimation = e.field("switchAnimator");
            e.call("detach");
            e.clear();
            e.switchTo(3, second);
            e.attached(true);
            oldPosts.forEach(Runnable::run);
            e.end(oldAnimation);
            e.assertRows(second);
            assertNull(e.field("justHighlightedLyricLine"));
        }
    }

    @Test void queuedHighlightCannotReviveClearedRows() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            Object oldRow = e.rows().getFirst();
            e.highlight(first);
            e.clear();
            e.drainHighlights();
            e.assertEmpty();
            assertEquals(0, oldRow.getClass().getField("emphasizes").getInt(oldRow));
            assertNull(e.field("justHighlightedLyricLine"));
        }
    }

    @Test void preClearQueuedDrainMayConsumeOnlyLatestTrackHighlight() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            Object oldRow = e.rows().getFirst();
            e.highlight(first);
            e.clear();
            e.switchTo(2, second);
            e.attached(true);
            e.manual();
            e.highlight(second); // Conflates into the previously scheduled drain.
            e.drainHighlights();
            assertEquals(0, oldRow.getClass().getField("emphasizes").getInt(oldRow));
            assertEquals(1, e.rows().getFirst().getClass().getField("emphasizes").getInt(e.rows().getFirst()));
            assertSame(second, e.field("justHighlightedLyricLine"));
            e.assertRows(second);
        }
    }

    @Test void lateListenerAfterDetachAndClearCannotScheduleOldHighlight() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            e.attached(true);
            e.call("attach");
            @SuppressWarnings("unchecked")
            var listeners = (Set<java.util.function.Consumer<LyricLine>>) e.now.getClass()
                    .getMethod("getLyricLineUpdateListener").invoke(e.now);
            assertEquals(1, listeners.size());
            var lateListener = listeners.iterator().next();
            List<Runnable> oldPosts = e.takeViewPosts();
            e.call("detach");
            assertTrue(listeners.isEmpty());
            e.clear();
            lateListener.accept(first);
            oldPosts.forEach(Runnable::run);
            e.drainHighlights();
            e.assertEmpty();
            assertNull(e.field("justHighlightedLyricLine"));
        }
    }

    @Test void queuedFrameFromBeforeClearCannotRestartFollowing() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            e.attached(true);
            var start = e.viewType.getSuperclass().getDeclaredMethod("startUpdateLoop");
            start.setAccessible(true);
            start.invoke(e.view);
            assertEquals(true, e.field("continueUpdate"));
            e.clear();
            e.drainFrames();
            assertEquals(false, e.field("continueUpdate"));
            e.assertEmpty();
            assertNull(e.field("justHighlightedLyricLine"));
        }
    }

    @Test void clearDoesNotRetainMotionRowsOrScheduleAnEmptyFollowingLoop() throws Exception {
        try (var e = new Environment(fixtures, context)) {
            e.switchTo(1, first);
            e.takeViewPosts();
            var pending = e.viewType.getSuperclass().getDeclaredField("pendingResyncToCurrent");
            pending.setAccessible(true); pending.setBoolean(e.view, true);
            e.clear();
            assertTrue(e.takeViewPosts().isEmpty(), "Cleared rows must not post another resync");
            assertEquals(0, ((icyllis.modernui.widget.LinearLayout) e.field("container")).getChildCount());
            assertTrue(((List<?>) e.field("rows")).isEmpty());
            assertEquals(false, e.field("pendingResyncToCurrent"));
            e.attached(true);
            e.call("resyncToCurrentLyric");
            assertEquals(false, e.field("continueUpdate"));
            e.assertEmpty();
        }
    }

    static final class Environment extends URLClassLoader {
        private static final String ROOT = "indi.mopelotus.musichud.";
        private static final String COMPONENT = ROOT + "client.ui.components.";
        final Path fixtures;
        final Object view;
        final Object now;
        final Class<?> viewType;
        final Set<String> productionClasses;
        Environment(Path fixtures, icyllis.modernui.core.Context context) throws Exception {
            this(fixtures, context, Map.of());
        }
        Environment(Path fixtures, icyllis.modernui.core.Context context, Map<String, String> extras) throws Exception {
            this(fixtures, context, extras, Set.of());
        }
        Environment(Path fixtures, icyllis.modernui.core.Context context, Map<String, String> extras, Set<String> productionClasses) throws Exception {
            super(new java.net.URL[]{fixtures.toUri().toURL()}, StaggeredLyricLifecycleTest.class.getClassLoader());
            this.productionClasses = productionClasses;
            this.fixtures = fixtures;
            compile(fixtures, extras);
            viewType = loadClass(COMPONENT + "LifecycleView");
            view = viewType.getConstructor(icyllis.modernui.core.Context.class).newInstance(context);
            now = loadClass(ROOT + "client.audio.NowPlayingInfo").getMethod("getInstance").invoke(null);
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                if (productionClasses.stream().anyMatch(type -> name.equals(type) || name.startsWith(type + "$")) || name.equals(COMPONENT + "StaggeredLyricScrollView") || name.startsWith(COMPONENT + "StaggeredLyricScrollView$")
                        || name.equals(COMPONENT + "MusicInfoCard") || name.startsWith(COMPONENT + "MusicInfoCard$")
                        || name.equals(COMPONENT + "PusherWidget")) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes(); result = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException ex) { throw new ClassNotFoundException(name, ex); }
                } else if (Files.exists(fixtures.resolve(name.replace('.', '/') + ".class"))) result = findClass(name);
                else result = super.loadClass(name, false);
            }
            if (resolve) resolveClass(result);
            return result;
        }
        Class<?> productionClass(String name) throws Exception {
            if (productionClasses.contains(name)) return loadClass(name);
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = Objects.requireNonNull(input).readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        Object field(String name) throws Exception {
            var f = viewType.getSuperclass().getDeclaredField(name); f.setAccessible(true); return f.get(view);
        }
        void manual() throws Exception {
            var f = viewType.getSuperclass().getDeclaredField("scrollStatus"); f.setAccessible(true);
            for (Object value : f.getType().getEnumConstants()) if (value.toString().equals("MANUAL")) f.set(view, value);
        }
        void call(String name) throws Exception { viewType.getMethod(name).invoke(view); }
        void attached(boolean value) throws Exception { viewType.getField("attached").setBoolean(view, value); }
        void clear() throws Exception { call("clearLyrics"); }
        void end(Object animation) throws Exception { animation.getClass().getMethod("fireEnd").invoke(animation); }
        void highlight(LyricLine line) throws Exception { viewType.getMethod("highlight", LyricLine.class).invoke(view, line); }
        void switchTo(int id, LyricLine line) throws Exception {
            var detail = MusicDetail.fromTuneWeave(id, "qq:" + id, "track", "album", 3000, null, List.of());
            now.getClass().getField("detail").set(now, detail);
            viewType.getMethod("switchLyrics", MusicDetail.class, Collection.class).invoke(view, detail, List.of(line));
        }
        @SuppressWarnings("unchecked") List<Object> rows() throws Exception { return (List<Object>) field("lyricLineViewList"); }
        void assertRows(LyricLine line) throws Exception {
            assertEquals(1, rows().size());
            assertSame(line, rows().getFirst().getClass().getField("line").get(rows().getFirst()));
        }
        void assertEmpty() throws Exception { assertTrue(rows().isEmpty()); assertTrue(((Map<?, ?>) field("lyricLines")).isEmpty()); }
        @SuppressWarnings("unchecked") void drainHighlights() throws Exception {
            var queue = (ArrayDeque<Runnable>) loadClass("icyllis.modernui.mc.MuiModApi").getField("queue").get(null);
            for (int i = 0; !queue.isEmpty() && i < 20; i++) queue.removeFirst().run();
            assertTrue(queue.isEmpty());
        }
        @SuppressWarnings("unchecked") void drainFrames() throws Exception {
            var queue = (ArrayDeque<Runnable>) loadClass("icyllis.modernui.core.Choreographer").getField("queue").get(null);
            assertEquals(1, queue.size());
            queue.removeFirst().run();
            assertTrue(queue.isEmpty(), "Stale frame must not schedule another frame");
        }
        List<Runnable> takeViewPosts() throws Exception {
            var f = icyllis.modernui.view.View.class.getDeclaredField("mRunQueue"); f.setAccessible(true);
            var queue = (icyllis.modernui.view.HandlerActionQueue) f.get(view);
            var result = new ArrayList<Runnable>();
            if (queue != null) {
                for (int i = 0; i < queue.size(); i++) result.add(queue.getRunnable(i));
                result.forEach(queue::removeCallbacks);
            }
            return result;
        }
        private static void compile(Path directory, Map<String, String> extras) throws Exception {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put(ROOT + "interfaces.ClientConfig", "public interface ClientConfig { static ClientConfig getInstance() { return () -> false; } boolean getEnableLyricBlur(); }");
            sources.put(ROOT + "MusicHud", "public class MusicHud { public static org.apache.logging.log4j.Logger getLogger(Class<?> type) { return org.apache.logging.log4j.LogManager.getLogger(type); } }");
            sources.put(ROOT + "client.audio.NowPlayingInfo", """
                    import java.util.*;
                    import java.util.function.Consumer;
                    import indi.mopelotus.musichud.beans.music.MusicDetail;
                    import indi.mopelotus.musichud.client.ui.dto.LyricLine;
                    public class NowPlayingInfo {
                        static final NowPlayingInfo INSTANCE = new NowPlayingInfo();
                        public MusicDetail detail = MusicDetail.NONE;
                        final Set<Consumer<LyricLine>> listeners = new HashSet<>();
                        public static NowPlayingInfo getInstance() { return INSTANCE; }
                        public MusicDetail getCurrentlyPlayingMusicDetail() { return detail; }
                        public LyricLine getCurrentLyricLine() { return null; }
                        public Set<Consumer<LyricLine>> getLyricLineUpdateListener() { return listeners; }
                    }
                    """);
            sources.put("icyllis.modernui.mc.ui.ClampingScrollView", "public class ClampingScrollView extends icyllis.modernui.widget.ScrollView { public ClampingScrollView(icyllis.modernui.core.Context context) { super(context); } }");
            sources.put("icyllis.modernui.mc.MuiModApi", "public class MuiModApi { public static final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>(); public static void postToUiThread(Runnable r) { queue.add(r); } public static long getElapsedTime() { return 0; } public static long getFrameTimeNanos() { return 0; } }");
            sources.put("icyllis.modernui.core.Choreographer", """
                    public class Choreographer {
                        static final Choreographer INSTANCE = new Choreographer();
                        public static final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
                        public static Choreographer getInstance() { return INSTANCE; }
                        public interface FrameCallback { void doFrame(Choreographer c, long nanos); }
                        public void postFrameCallback(FrameCallback callback) { queue.add(() -> callback.doFrame(this, 0)); }
                    }
                    """);
            sources.put("icyllis.modernui.animation.ObjectAnimator", """
                    public class ObjectAnimator extends ValueAnimator {
                        public static <T> ObjectAnimator ofFloat(T target, icyllis.modernui.util.FloatProperty<T> property, float... values) { return new ObjectAnimator(); }
                        @Override public ObjectAnimator setDuration(long duration) { return this; }
                        @Override public void start() {}
                        @Override public void cancel() { fireEnd(); }
                        public void fireEnd() { if (getListeners() != null) for (var listener : getListeners()) listener.onAnimationEnd(this); }
                    }
                    """);
            sources.put(COMPONENT + "LyricLineView", """
                    import icyllis.modernui.core.Context;
                    import indi.mopelotus.musichud.client.ui.dto.LyricLine;
                    public class LyricLineView extends icyllis.modernui.widget.LinearLayout {
                        public final LyricLine line; public int emphasizes;
                        public LyricLineView(Context c, LyricLine l, int main, int sub) { super(c); line = l; }
                        public void emphasize() { emphasizes++; }
                        public float getTargetOffset(LyricLine l) { return 0; }
                        public int getScrollPosition(StaggeredLyricScrollView v) { return 0; }
                        public void releaseBlurResources() {} public void releaseBlurSurfaces() {} public void refreshSubLyricLine() {}
                        public boolean isBlurEligible() { return false; } public boolean isBlurInitialized() { return true; }
                        public void setBlurTarget(float f, long n) {} public void updateBlur(long n) {} public void snapBlur() {} public void markBlurInitialized() {}
                    }
                    """);
            sources.put(COMPONENT + "LifecycleView", """
                    public class LifecycleView extends StaggeredLyricScrollView {
                        public boolean attached;
                        public LifecycleView(icyllis.modernui.core.Context context) { super(context); }
                        @Override public boolean isAttachedToWindow() { return attached; }
                        public void attach() { onAttachedToWindow(); }
                        public void detach() { attached = false; onDetachedFromWindow(); }
                        public void highlight(indi.mopelotus.musichud.client.ui.dto.LyricLine line) { highlightLine(line); }
                    }
                    """);
            sources.putAll(extras);
            String classpath = System.getProperty("java.class.path");
            for (Class<?> type : List.of(icyllis.modernui.core.Context.class, MusicDetail.class, LyricLine.class, org.apache.logging.log4j.Logger.class))
                classpath += File.pathSeparator + Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classpath, "-d", directory.toString()));
            for (var source : sources.entrySet()) {
                Path path = directory.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "package " + source.getKey().substring(0, source.getKey().lastIndexOf('.')) + ";\n" + source.getValue());
                arguments.add(path.toString());
            }
            var diagnostics = new ByteArrayOutputStream();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)), diagnostics::toString);
        }
    }
}
