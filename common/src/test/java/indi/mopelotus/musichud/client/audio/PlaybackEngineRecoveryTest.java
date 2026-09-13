package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackEngineRecoveryTest {
    @Test void repeatedRecoveryBeforeStarterRunsPreservesOriginalBookkeeping() throws Exception {
        var original = engine();
        PlaybackSession session = session();
        set(original, "currentPlaybackSession", session);
        var ledger = (PlaybackListeningLedger) get(original, "listeningLedger");
        ledger.begin(0, session.sessionId(), "");
        ledger.queue(0, 1, 60_000, 1000, true);
        ledger.observe(0, 12, true);
        var gate = (PlaybackSubmissionGate) get(original, "scrobbleGate");
        gate.activate(0, session.sessionId());
        gate.claim(0, true);
        BiConsumer<Long, MusicResourceInfo> accountCapture = (time, resource) -> fail("Recovery must not submit");
        set(original, "preparedScrobble", accountCapture);

        var first = original.replaceForRecovery(session);
        assertSame(session, first.session());
        Object checkpoint = get(first, "recovery");
        assertEquals(12_000L, component(checkpoint, "consumedMillis"));
        assertEquals(true, component(checkpoint, "submitted"));
        assertSame(accountCapture, component(checkpoint, "submitter"));

        List<Runnable> scheduled = new ArrayList<>();
        var handoff = new PlaybackHandoff<PlaybackEngine>(scheduled::add, ignored -> {}, () -> 0);
        var oldResult = handoff.begin(first, lane -> {
            fail("Superseded starter must never execute");
            return CompletableFuture.completedFuture(session.startTime());
        });
        assertSame(first, handoff.pending());
        var second = handoff.pending().replaceForRecovery(session);
        handoff.stop();
        assertSame(session, second.session());
        assertSame(checkpoint, get(second, "recovery"));
        assertNull(get(first, "recovery"));
        assertTrue(original.playSessionAsync(session).isCompletedExceptionally());
        assertTrue(first.playSessionAsync(session).isCompletedExceptionally());
        while (!scheduled.isEmpty()) scheduled.removeFirst().run();
        assertTrue(oldResult.isCompletedExceptionally());
        assertSame(checkpoint, get(second, "recovery"));
        second.stop();
        assertSame(PlaybackSession.NONE, second.session());
        assertNull(get(second, "recovery"));
    }

    @Test void differentPublicSessionCannotInheritPendingAccountCapture() throws Exception {
        var original = engine();
        PlaybackSession a = session(), b = session();
        set(original, "currentPlaybackSession", a);
        var pending = original.replaceForRecovery(a);
        var replacement = pending.replaceForRecovery(b);
        assertNull(get(replacement, "recovery"));
        assertSame(PlaybackSession.NONE, replacement.session());
        assertTrue(pending.playSessionAsync(a).isCompletedExceptionally());
        replacement.stop();
    }

    private static PlaybackEngine engine() {
        ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(),
                new Class<?>[]{ClientConfig.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected config access: " + method.getName());
                });
        return new PlaybackEngine(config);
    }

    private static PlaybackSession session() {
        MusicDetail song = MusicDetail.fromTuneWeave(1, "netease:track:1", "track", "Song", 180_000,
                Album.NONE, List.of());
        return new PlaybackSession(UUID.randomUUID(), 1, 0, song, MusicResourceInfo.NONE,
                ZonedDateTime.parse("2026-09-12T12:00:00Z"));
    }

    // Seed a consumed-audio checkpoint without booting Minecraft or an OpenAL device.
    private static Object get(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object component(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record);
    }
}
