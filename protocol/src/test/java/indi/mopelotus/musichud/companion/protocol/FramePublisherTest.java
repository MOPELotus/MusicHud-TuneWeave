package indi.mopelotus.musichud.companion.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FramePublisherTest {
    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private Socket connect(FramePublisher publisher) throws Exception {
        var socket = new Socket("127.0.0.1", publisher.port());
        socket.setSoTimeout(2500);
        return socket;
    }
    private CompanionWire.Message until(DataInputStream input, CompanionWire.Kind kind) throws IOException {
        for (int i = 0; i < 20; i++) {
            var message = CompanionWire.read(input);
            if (message.kind() == kind) return message;
        }
        throw new AssertionError("Expected " + kind);
    }
    @Test void lateCapturesCannotUndoClearOrReplaceNewerFrame() throws Exception {
        try (var publisher = new FramePublisher(0); var socket = connect(publisher)) {
            var input = new DataInputStream(socket.getInputStream());
            assertTrue(CompanionWire.read(input).locked());
            var stale = publisher.capture();
            publisher.clear();
            publisher.publish(stale, 1, 1, new byte[]{1, 0, 0, 1});
            assertEquals(CompanionWire.Kind.CLEAR, CompanionWire.read(input).kind());
            var older = publisher.capture();
            var newer = publisher.capture();
            publisher.publish(newer, 1, 1, new byte[]{2, 0, 0, 2});
            publisher.publish(older, 1, 1, new byte[]{1, 0, 0, 1});
            assertEquals(2, until(input, CompanionWire.Kind.FRAME).rgba()[0]);
            publisher.setLocked(false);
            assertFalse(until(input, CompanionWire.Kind.STATUS).locked());
            publisher.setLocked(true);
            assertTrue(until(input, CompanionWire.Kind.STATUS).locked());
        }
    }
    @Test void reconnectLocksAndRejectsPreviousConnectionCallbacks() throws Exception {
        try (var publisher = new FramePublisher(0); var first = connect(publisher)) {
            var firstInput = new DataInputStream(first.getInputStream());
            var initial = CompanionWire.read(firstInput);
            publisher.setLocked(false);
            var stale = publisher.capture();
            try (var second = connect(publisher)) {
                var input = new DataInputStream(second.getInputStream());
                var next = CompanionWire.read(input);
                assertTrue(next.locked());
                assertNotEquals(initial.generation(), next.generation());
                publisher.setLocked(initial.generation(), false);
                assertTrue(publisher.locked(), "A queued unlock from the previous window must be discarded");
                publisher.setLocked(next.generation(), false);
                assertFalse(publisher.locked());
                publisher.setLocked(next.generation(), true);
                assertTrue(publisher.locked());
                publisher.publish(stale, 1, 1, new byte[4]);
                publisher.publish(publisher.capture(), 1, 1, new byte[]{3, 0, 0, 3});
                assertEquals(3, until(input, CompanionWire.Kind.FRAME).rgba()[0]);
            }
        }
    }
    @Test void closeCannotBeUndoneByLateCallbacks() throws Exception {
        var publisher = new FramePublisher(0);
        try (var socket = connect(publisher)) {
            CompanionWire.read(new DataInputStream(socket.getInputStream()));
            var capture = publisher.capture();
            publisher.close();
            publisher.publish(capture, 1, 1, new byte[4]);
            publisher.setLocked(false);
            assertFalse(publisher.connected());
            assertTrue(publisher.locked());
            assertNull(publisher.capture());
        } finally { publisher.close(); }
    }
}
