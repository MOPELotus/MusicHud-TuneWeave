package indi.mopelotus.musichud.companion.desktop;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import org.junit.jupiter.api.Test;
import java.io.DataOutputStream;
import java.net.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FrameReceiverTest {
    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Receiver did not reach expected state");
    }

    @Test void disconnectClearsAndReconnectStartsFresh() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var receiver = new FrameReceiver(server.getLocalPort())) {
            server.setSoTimeout(3000);
            try (var peer = server.accept()) {
                CompanionWire.write(new DataOutputStream(peer.getOutputStream()), new CompanionWire.Message(
                        CompanionWire.Kind.FRAME, 1, 1, 0, false, 1, 1, new byte[4]));
                await(() -> receiver.state.get().frame() != null);
            }
            await(() -> receiver.state.get().locked() && receiver.state.get().frame() == null);
            try (var peer = server.accept()) {
                CompanionWire.write(new DataOutputStream(peer.getOutputStream()), new CompanionWire.Message(
                        CompanionWire.Kind.STATUS, 2, 0, 0, true, 0, 0, new byte[0]));
                await(() -> receiver.state.get().generation() == 2);
                assertTrue(receiver.state.get().locked());
                assertNull(receiver.state.get().frame());
            }
        }
    }

    @Test void closeWhileReadingAPartialFrameCannotPublishLatePixels() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var receiver = new FrameReceiver(server.getLocalPort());
             var peer = server.accept()) {
            var bytes = new java.io.ByteArrayOutputStream();
            CompanionWire.write(new DataOutputStream(bytes), new CompanionWire.Message(
                    CompanionWire.Kind.FRAME, 5, 1, 0, false, 1, 1, new byte[4]));
            byte[] frame = bytes.toByteArray();
            peer.getOutputStream().write(frame, 0, frame.length - 2);
            receiver.close();
            try { peer.getOutputStream().write(frame, frame.length - 2, 2); }
            catch (java.io.IOException ignored) {}
            await(() -> receiver.state.get().generation() == -1 && receiver.state.get().locked());
            assertNull(receiver.state.get().frame());
        }
    }
}
