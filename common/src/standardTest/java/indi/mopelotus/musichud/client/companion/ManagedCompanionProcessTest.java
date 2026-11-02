package indi.mopelotus.musichud.client.companion;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ManagedCompanionProcessTest {
    static final class Child implements ManagedCompanionProcess.Child {
        boolean stopped;
        public void stop() { stopped = true; }
        public boolean alive() { return !stopped; }
    }
    @Test void lateDownloadAfterStopCannotBecomeOwnedChild() {
        var jobs = new ArrayDeque<Runnable>();
        var process = new ManagedCompanionProcess(jobs::add);
        var child = new Child();
        process.start(token -> { assertTrue(token.get()); return child; });
        process.stop();
        jobs.remove().run();
        assertTrue(child.stopped);
        assertFalse(process.running());
        assertFalse(process.busy());
    }
    @Test void oldCompletionCannotReplaceNewGeneration() {
        var jobs = new ArrayDeque<Runnable>();
        var process = new ManagedCompanionProcess(jobs::add);
        var old = new Child();
        var current = new Child();
        process.start(token -> old);
        var oldJob = jobs.remove();
        process.stop();
        process.start(token -> current);
        jobs.remove().run();
        oldJob.run();
        assertTrue(old.stopped);
        assertFalse(current.stopped);
        assertTrue(process.running());
        process.stop();
        assertTrue(current.stopped);
    }
    @Test void shutdownRejectsFutureStartsAndStopsLateChild() {
        var jobs = new ArrayDeque<Runnable>();
        var process = new ManagedCompanionProcess(jobs::add);
        var child = new Child();
        process.start(token -> child);
        process.close();
        process.start(token -> { fail("closed"); return null; });
        assertEquals(1, jobs.size());
        jobs.remove().run();
        assertTrue(child.stopped);
    }
    @Test void failureFromCancelledGenerationCannotOverwriteCurrentStatus() {
        var jobs = new ArrayDeque<Runnable>();
        var process = new ManagedCompanionProcess(jobs::add);
        process.start(token -> { throw new Exception("old failure"); });
        var oldJob = jobs.remove();
        process.stop();
        process.start(token -> { throw new Exception("new failure"); });
        jobs.remove().run();
        oldJob.run();
        assertEquals("new failure", process.failure());
    }
    @Test void doubleStartDoesNotLaunchDuplicateChildren() {
        var jobs = new ArrayDeque<Runnable>();
        var process = new ManagedCompanionProcess(jobs::add);
        process.start(token -> new Child());
        process.start(token -> { fail("duplicate"); return null; });
        assertEquals(1, jobs.size());
        jobs.remove().run();
        process.start(token -> { fail("already running"); return null; });
        assertTrue(jobs.isEmpty());
    }
}
