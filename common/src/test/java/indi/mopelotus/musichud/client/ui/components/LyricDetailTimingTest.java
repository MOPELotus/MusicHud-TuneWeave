package indi.mopelotus.musichud.client.ui.components;

import indi.mopelotus.musichud.client.ui.dto.LyricLine;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

class LyricDetailTimingTest {
    @TempDir Path fixtures;
    private final LayoutTestContext context = new LayoutTestContext();

    @Test void unknownTrackDurationAfterStopCannotBreakEmphasisAndKnownLineDurationStillWins() throws Exception {
        try (var e = new StaggeredLyricLifecycleTest.Environment(fixtures, context, java.util.Map.of(), java.util.Set.of("indi.mopelotus.musichud.client.ui.components.LyricLineView"))) {
            Class<?> type = e.productionClass("indi.mopelotus.musichud.client.ui.components.LyricLineView");
            Method timing = type.getDeclaredMethod("emphasizeDuration", Duration.class, Duration.class, Duration.class, Duration.class);
            timing.setAccessible(true);
            // A queued row callback may run after stop has cleared the track duration.
            assertEquals(Duration.ofMillis(900), timing.invoke(null, null, null, Duration.ofSeconds(4), Duration.ZERO));
            assertEquals(Duration.ofMillis(900), timing.invoke(null, null, Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ZERO));
            assertEquals(Duration.ofMillis(1200), timing.invoke(null, Duration.ofSeconds(3), null, Duration.ofSeconds(4), Duration.ofSeconds(1)));
            assertEquals(Duration.ZERO, timing.invoke(null, Duration.ofMillis(800), null, Duration.ZERO, Duration.ZERO));
            assertEquals(Duration.ofMillis(900), timing.invoke(null, Duration.ofMillis(799), null, Duration.ZERO, Duration.ZERO));
            assertEquals(Duration.ofMillis(4200), timing.invoke(null, null, Duration.ofSeconds(9), Duration.ofSeconds(4), Duration.ZERO));
        }
    }

    @Test void queuedRealRowEmphasisSurvivesDurationBeingClearedByStop() throws Exception {
        var extras = java.util.Map.of(
            "indi.mopelotus.musichud.client.audio.NowPlayingInfo", """
                public class NowPlayingInfo {
                    static final NowPlayingInfo INSTANCE=new NowPlayingInfo();
                    public volatile java.time.Duration duration=java.time.Duration.ofSeconds(10);
                    public int reads;
                    public static NowPlayingInfo getInstance(){ return INSTANCE; }
                    public java.time.Duration getPlayedDuration(){ return java.time.Duration.ZERO; }
                    public java.time.Duration getMusicDuration(){ reads++; return duration; }
                }
                """,
            "indi.mopelotus.musichud.client.ui.components.LyricHighlightTextView", """
                public class LyricHighlightTextView extends icyllis.modernui.widget.TextView {
                    public LyricHighlightTextView(icyllis.modernui.core.Context c, indi.mopelotus.musichud.client.ui.dto.LyricLine l){ super(c); }
                    public void emphasize(){} public void setOnFade(Runnable r){}
                }
                """);
        try (var e = new StaggeredLyricLifecycleTest.Environment(fixtures, context, extras, java.util.Set.of("indi.mopelotus.musichud.client.ui.components.LyricLineView"))) {
            Class<?> type = e.productionClass("indi.mopelotus.musichud.client.ui.components.LyricLineView");
            var line = LyricLine.builder().text("metadata").type(LyricLine.Type.META_DATA).startTime(Duration.ofSeconds(4)).build();
            Object row = type.getConstructor(icyllis.modernui.core.Context.class, LyricLine.class).newInstance(context, line);
            java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
            Thread stop = Thread.ofPlatform().start(() -> {
                try { e.now.getClass().getField("duration").set(e.now, null); }
                catch (Exception error) { throw new AssertionError(error); }
                finally { stopped.countDown(); }
            });
            stopped.await(); stop.join();
            type.getMethod("emphasize").invoke(row);
            assertEquals(1, e.now.getClass().getField("reads").getInt(e.now));
            var queueField = icyllis.modernui.view.View.class.getDeclaredField("mRunQueue"); queueField.setAccessible(true);
            var queue = (icyllis.modernui.view.HandlerActionQueue)queueField.get(row);
            assertNotNull(queue); assertEquals(1, queue.size());
        }
    }

    @Test void recenterWaveUsesZeroTargetDelayBeforeReturningToFollowMode() throws Exception {
        try (var e = new StaggeredLyricLifecycleTest.Environment(fixtures, context)) {
            var lines = new ArrayDeque<LyricLine>();
            for (int i = 0; i < 5; i++) lines.add(LyricLine.builder().text("row"+i).type(LyricLine.Type.NORMAL).build());
            Class<?> type = e.viewType.getSuperclass();
            // Supply resource-free rows while executing the production scroll state machine.
            @SuppressWarnings("unchecked") var rows = (java.util.List<Object>) e.field("lyricLineViewList");
            Class<?> row = e.loadClass("indi.mopelotus.musichud.client.ui.components.LyricLineView");
            for (var line : lines) rows.add(row.getConstructor(icyllis.modernui.core.Context.class, LyricLine.class, int.class, int.class).newInstance(context, line, 28, 16));
            var state = type.getDeclaredField("scrollStatus"); state.setAccessible(true);
            @SuppressWarnings({"rawtypes", "unchecked"}) Object recenter = Enum.valueOf((Class)state.getType(), "RECENTER");
            @SuppressWarnings({"rawtypes", "unchecked"}) Object follow = Enum.valueOf((Class)state.getType(), "FOLLOW_LYRICS");
            Method calc = type.getDeclaredMethod("calcLoggedDelay", int.class, LyricLine.class); calc.setAccessible(true);
            state.set(e.view, follow); calc.invoke(e.view, 2, null);
            float[] ordinary = ((float[])e.field("delayMillis")).clone();
            state.set(e.view, recenter); calc.invoke(e.view, 2, null);
            float[] recentered = (float[])e.field("delayMillis");
            assertEquals(0, recentered[2]);
            assertTrue(ordinary[2] > 0);
            assertEquals(ordinary[2]/2, recentered[3], .0001);
            assertEquals(550, constant(type, "SCROLL_RESPONSE_MILLIS"));
            assertEquals(.95f, (float)constant(type, "SCROLL_DAMPING"));
            ((icyllis.modernui.view.View)e.view).setBottom(200);
            ((icyllis.modernui.view.View)e.field("container")).setBottom(1000);
            state.set(e.view, recenter);
            Method scroll = type.getDeclaredMethod("scrollToLyric", row); scroll.setAccessible(true);
            scroll.invoke(e.view, rows.get(2));
            assertEquals(follow, state.get(e.view));
            assertEquals(0, ((float[])e.field("delayMillis"))[2], "Actual scroll must compute delays before leaving RECENTER");
            var highlight = type.getDeclaredField("justHighlightedLyricLine"); highlight.setAccessible(true);
            highlight.set(e.view, lines.stream().skip(2).findFirst().orElseThrow());
            @SuppressWarnings("unchecked") var byLine = (java.util.Map<LyricLine,Object>) e.field("lyricLines");
            byLine.put((LyricLine)highlight.get(e.view), rows.get(2));
            ((icyllis.modernui.view.View)e.view).scrollTo(0, 300);
            state.set(e.view, recenter);
            e.attached(true);
            type.getMethod("refreshLinesStyle").invoke(e.view);
            e.takeViewPosts().forEach(Runnable::run);
            assertEquals(0, ((icyllis.modernui.view.View)e.view).getScrollY(), "Style refresh must jump even during an existing recenter");
        }
    }
    private Object constant(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
}
