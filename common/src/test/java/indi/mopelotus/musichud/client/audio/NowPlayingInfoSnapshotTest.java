package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real publication/getter code without Minecraft or native media initialization. */
class NowPlayingInfoSnapshotTest {
    @TempDir Path fixtures;
    private static final UUID PUSHER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test void gettersUsePublishedStateDuringAnUnpublishedStopOrSwitch() throws Exception {
        try (var e = new Environment(fixtures)) {
            assertEquals(Duration.ZERO, e.call("getPlayedDuration"));
            assertNull(e.call("getPusherPlayerInfo"));
            MusicDetail track = track(10_000);
            e.switchTo(track, ZonedDateTime.now().minusHours(1));
            // stop()/switchMusicInfoAt() update these fields before publishing their new snapshot.
            // Reproduce that window deterministically: readers must keep the previous complete state.
            e.set("musicStartTime", null);
            e.set("musicDuration", null);
            e.set("currentlyPlayingMusicDetail", MusicDetail.NONE);
            assertEquals(1f, e.call("getProgressRate"));
            assertEquals(Duration.ofSeconds(10), e.call("getPlayedDuration"));
            assertEquals(true, e.call("isCompleted"));
            e.call("getPusherPlayerInfo");
            assertEquals(PUSHER, e.lastPusher());
            e.call("stop");
            assertEquals(0f, e.call("getProgressRate"));
            assertEquals(Duration.ZERO, e.call("getPlayedDuration"));
            assertEquals(false, e.call("isCompleted"));
            e.call("getPusherPlayerInfo");
            assertEquals(PusherInfo.EMPTY.getPlayerUUID(), e.lastPusher());
        }
    }

    @Test void futureUnstartedAndZeroDurationKeepTheirExistingSemantics() throws Exception {
        try (var e = new Environment(fixtures)) {
            e.switchTo(track(10_000), null);
            assertEquals(Duration.ZERO, e.call("getPlayedDuration"));
            assertEquals(false, e.call("isCompleted"));
            e.switchTo(track(0), ZonedDateTime.now().plusDays(1));
            assertEquals(0f, e.call("getProgressRate"));
            assertEquals(Duration.ZERO, e.call("getPlayedDuration"));
            assertEquals(false, e.call("isCompleted"));
            e.switchTo(track(0), ZonedDateTime.now().minusDays(1));
            assertEquals(0f, e.call("getProgressRate"));
            assertEquals(Duration.ZERO, e.call("getPlayedDuration"));
            assertEquals(true, e.call("isCompleted"));
        }
    }

    @Test void concurrentStopAndRapidSwitchNeverExposePartialTimelines() throws Exception {
        try (var e = new Environment(fixtures); var workers = Executors.newFixedThreadPool(4)) {
            MusicDetail finished = track(10_000), future = track(172_800_000);
            ZonedDateTime old = ZonedDateTime.now().minusHours(1), later = ZonedDateTime.now().plusDays(1);
            var start = new CountDownLatch(1);
            List<Callable<Void>> tasks = new ArrayList<>();
            tasks.add(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    e.switchTo(finished, old);
                    e.switchTo(future, later);
                    e.call("stop");
                }
                return null;
            });
            for (int reader = 0; reader < 3; reader++) tasks.add(() -> {
                start.await();
                for (int i = 0; i < 8_000; i++) {
                    float progress = (Float) e.call("getProgressRate");
                    assertTrue(progress == 0f || progress == 1f, "Mixed start/duration: " + progress);
                    Duration played = (Duration) e.call("getPlayedDuration");
                    assertTrue(Duration.ZERO.equals(played) || Duration.ofSeconds(10).equals(played),
                            "Mixed start/duration: " + played);
                    assertNotNull(e.call("isCompleted"));
                    e.call("getPusherPlayerInfo");
                }
                return null;
            });
            List<Future<Void>> results = new ArrayList<>();
            for (var task : tasks) results.add(workers.submit(task));
            start.countDown();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
    }

    private static MusicDetail track(int millis) {
        MusicDetail track = MusicDetail.fromTuneWeave(42, "qq:42", "track", "album", millis, null, List.of());
        track.setPusherInfo(new PusherInfo(PUSHER, "pusher"));
        return track;
    }

    private static final class Environment extends URLClassLoader {
        private static final String ROOT = "indi.mopelotus.musichud.";
        private static final String AUDIO = ROOT + "client.audio.";
        private final Path fixtures;
        private final Class<?> type;
        private final Object now;

        Environment(Path fixtures) throws Exception {
            super(new java.net.URL[]{fixtures.toUri().toURL()}, NowPlayingInfoSnapshotTest.class.getClassLoader());
            this.fixtures = fixtures;
            compile(fixtures);
            type = loadClass(AUDIO + "NowPlayingInfo");
            now = type.getMethod("getInstance").invoke(null);
        }

        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(AUDIO + "NowPlayingInfo") || name.startsWith(AUDIO + "LatestMediaArtwork")
                        || name.equals(AUDIO + "ListenerDispatcher")) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                } else if (Files.exists(fixtures.resolve(name.replace('.', '/') + ".class"))) loaded = findClass(name);
                else loaded = super.loadClass(name, false);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        Object call(String name) throws Exception { return type.getMethod(name).invoke(now); }
        void switchTo(MusicDetail track, ZonedDateTime start) throws Exception {
            type.getMethod("switchMusicInfoAt", MusicDetail.class, MusicDetail.class, ZonedDateTime.class)
                    .invoke(now, track, MusicDetail.NONE, start);
        }
        void set(String name, Object value) throws Exception {
            var field = type.getDeclaredField(name); field.setAccessible(true); field.set(now, value);
        }
        UUID lastPusher() throws Exception {
            return (UUID) loadClass(ROOT + "client.utils.PlayerInfoUtil").getField("last").get(null);
        }
        @Override public void close() throws java.io.IOException {
            try {
                var field = type.getDeclaredField("mediaExecutor"); field.setAccessible(true);
                ((ScheduledExecutorService) field.get(now)).shutdownNow();
            } catch (ReflectiveOperationException failure) { throw new java.io.IOException(failure); }
            finally { super.close(); }
        }

        private static void compile(Path directory) throws Exception {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put(ROOT + "MusicHud", "public class MusicHud { public static final String DISPLAY_NAME=\"test\"; public static final java.util.concurrent.ExecutorService EXECUTOR=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); public static org.apache.logging.log4j.Logger getLogger(Class<?> c) { return org.apache.logging.log4j.LogManager.getLogger(c); } }");
            sources.put(ROOT + "interfaces.ClientConfig", "public interface ClientConfig { static ClientConfig getInstance() { return null; } }");
            sources.put(ROOT + "client.ui.hud.HudRendererManager", "public class HudRendererManager { public static HudRendererManager getInstance() { return new HudRendererManager(); } public void switchMusic(indi.mopelotus.musichud.beans.music.MusicDetail detail) {} }");
            sources.put("io.github.selemba1000.JMTC", "public class JMTC { public static JMTC getInstance(JMTCSettings settings) { throw new UnsupportedOperationException(\"No native media in test\"); } }");
            sources.put("net.minecraft.client.multiplayer.PlayerInfo", "public class PlayerInfo {}");
            sources.put(ROOT + "client.utils.PlayerInfoUtil", "public class PlayerInfoUtil { public static volatile java.util.UUID last; public static net.minecraft.client.multiplayer.PlayerInfo getPlayerInfoByUUID(java.util.UUID id) { last=id; return null; } }");
            Set<String> classpath = new LinkedHashSet<>(List.of(System.getProperty("java.class.path")));
            for (Class<?> c : List.of(MusicDetail.class, org.apache.logging.log4j.Logger.class,
                    io.github.selemba1000.JMTCSettings.class)) {
                classpath.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            }
            List<String> arguments = new ArrayList<>(List.of("-proc:none", "-classpath", String.join(File.pathSeparator, classpath), "-d", directory.toString()));
            for (var source : sources.entrySet()) {
                Path file = directory.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "package " + source.getKey().substring(0, source.getKey().lastIndexOf('.')) + ";\n" + source.getValue());
                arguments.add(file.toString());
            }
            var output = new ByteArrayOutputStream();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, output, output, arguments.toArray(String[]::new)), output::toString);
        }
    }
}
