package indi.mopelotus.musichud.client.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApiLoginRestoreGateTest {
    @Test void earlyRunningWaitsForClientAndPlayerThenRestoresOnce() throws Exception {
        var gate = new ApiLoginRestoreGate();
        Object manager = new Object();
        var restores = new AtomicInteger();
        gate.bind(manager, false);
        Thread worker = new Thread(() -> gate.status(manager, true));
        worker.start();
        worker.join();
        assertEquals(0, restores.get(), "API worker must never access client state");
        gate.tick(() -> false, restores::incrementAndGet);
        gate.tick(() -> false, restores::incrementAndGet);
        assertEquals(0, restores.get());
        gate.tick(() -> true, restores::incrementAndGet);
        gate.tick(() -> true, restores::incrementAndGet);
        gate.status(manager, true);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(1, restores.get());
    }

    @Test void bindingAlreadyRunningReplaysWithoutAnotherStatusEvent() {
        var gate = new ApiLoginRestoreGate();
        var restores = new AtomicInteger();
        gate.bind(new Object(), true);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(1, restores.get());
    }

    @Test void stopBeforeReadyDiscardsPendingRestoreAndRestartCreatesOneNewIntent() {
        var gate = new ApiLoginRestoreGate();
        Object manager = new Object();
        var restores = new AtomicInteger();
        gate.bind(manager, true);
        gate.tick(() -> false, restores::incrementAndGet);
        gate.status(manager, false);
        gate.tick(() -> { fail("Stopped service must not consult client state"); return true; }, restores::incrementAndGet);
        gate.status(manager, true);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(1, restores.get());
    }

    @Test void stopOrStopRestartDuringReadyCheckCannotConsumeNewGeneration() {
        for (boolean restart : new boolean[]{false, true}) {
            var gate = new ApiLoginRestoreGate();
            Object manager = new Object();
            var restores = new AtomicInteger();
            gate.bind(manager, true);
            gate.tick(() -> {
                gate.status(manager, false);
                if (restart) gate.status(manager, true);
                return true;
            }, restores::incrementAndGet);
            assertEquals(0, restores.get());
            gate.tick(() -> true, restores::incrementAndGet);
            assertEquals(restart ? 1 : 0, restores.get());
        }
    }

    @Test void detachedManagerLateCallbacksCannotRestoreOrCancelReplacement() {
        var gate = new ApiLoginRestoreGate();
        Object old = new Object(), current = new Object();
        var restores = new AtomicInteger();
        gate.bind(old, true);
        gate.bind(current, false);
        gate.status(old, true);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(0, restores.get());
        gate.status(current, true);
        gate.status(old, false);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(1, restores.get());
        gate.bind(null, false);
        gate.status(current, true);
        gate.tick(() -> true, restores::incrementAndGet);
        assertEquals(1, restores.get());
    }

    @Test void readyAndRestoreAreNotExecutedUnderTheStatusGateLock() {
        var gate = new ApiLoginRestoreGate();
        gate.bind(new Object(), true);
        gate.tick(() -> { assertFalse(Thread.holdsLock(gate)); return true; },
                () -> assertFalse(Thread.holdsLock(gate)));
    }
}
