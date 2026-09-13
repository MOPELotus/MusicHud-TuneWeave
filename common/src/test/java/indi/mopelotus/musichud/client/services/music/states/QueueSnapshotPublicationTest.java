package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.QueueItem;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class QueueSnapshotPublicationTest {
    @Test void initialResponseRetiresPushQueuedBeforeTheRequest() {
        Fixture f = new Fixture();
        Runnable old = f.state.prepare(queue(1));
        long requested = f.state.revision();
        assertTrue(f.state.publishInitialIfUnchanged(requested, queue(2)));
        old.run();
        assertEquals(List.of(2), f.published);
    }

    @Test void pushReceivedDuringRequestWinsEvenBeforeItsWorkerRuns() {
        Fixture f = new Fixture();
        long requested = f.state.revision();
        Runnable newest = f.state.prepare(queue(0));
        assertFalse(f.state.publishInitialIfUnchanged(requested, queue(1)));
        newest.run();
        assertEquals(List.of(0), f.published);
    }

    @Test void alreadyCompletedLocalResponseCannotOverwritePushBeforeCallbackRegistration() {
        Fixture f = new Fixture();
        long requested = f.state.revision();
        var response = CompletableFuture.completedFuture(queue(1));
        f.state.prepare(queue(0)).run();
        response.thenAccept(queue -> f.state.publishInitialIfUnchanged(requested, queue));
        assertEquals(List.of(0), f.published);
    }

    @Test void publishingAnEarlierReceivedPushDoesNotMakeAnInitialResponseStale() {
        Fixture f = new Fixture();
        Runnable earlier = f.state.prepare(queue(1));
        long requested = f.state.revision();
        earlier.run();
        assertTrue(f.state.publishInitialIfUnchanged(requested, queue(0)));
        assertEquals(List.of(1, 0), f.published);
    }

    @Test void disconnectInvalidatesBothQueuedPushAndPendingInitialResponse() {
        Fixture f = new Fixture();
        Runnable old = f.state.prepare(queue(1));
        long requested = f.state.revision();
        synchronized (f.lock) { f.state.invalidate(); f.published.clear(); }
        old.run();
        assertFalse(f.state.publishInitialIfUnchanged(requested, queue(2)));
        assertTrue(f.published.isEmpty());
        f.state.prepare(queue(3)).run();
        assertEquals(List.of(3), f.published);
    }

    @Test void retryCapturesNewRevisionAndInitialResponseOwnsItsQueue() {
        Fixture f = new Fixture();
        long first = f.state.revision();
        f.state.prepare(queue(1)).run();
        assertFalse(f.state.publishInitialIfUnchanged(first, queue(2)));
        long retry = f.state.revision();
        var input = queue(3);
        assertTrue(f.state.publishInitialIfUnchanged(retry, input));
        input.clear();
        assertEquals(3, f.last.size());
        assertEquals(List.of(1, 3), f.published);
    }

    private static Queue<QueueItem> queue(int size) {
        Queue<QueueItem> result = new ArrayDeque<>();
        for (int i = 0; i < size; i++) result.add(new QueueItem(MusicDetail.NONE, new UUID(0, i + 1)));
        return result;
    }

    private static final class Fixture {
        final Object lock = new Object();
        final List<Integer> published = new ArrayList<>();
        Queue<QueueItem> last;
        final QueueSnapshotPublication state = new QueueSnapshotPublication(lock, queue -> {
            assertTrue(Thread.holdsLock(lock)); last = queue; published.add(queue.size());
        });
    }
}
