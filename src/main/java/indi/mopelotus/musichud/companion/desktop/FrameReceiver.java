package indi.mopelotus.musichud.companion.desktop;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

final class FrameReceiver implements AutoCloseable {
    final AtomicReference<DisplayState> state = new AtomicReference<>(DisplayState.empty());
    private final int port;
    private volatile boolean closed;
    private volatile Socket socket;
    private final Thread worker;

    FrameReceiver(int port) {
        this.port = port;
        worker = Thread.ofVirtual().name("companion-receiver").start(this::receive);
    }

    private void receive() {
        while (!closed) {
            state.set(DisplayState.empty());
            try (Socket connection = new Socket()) {
                socket = connection;
                if (closed) break;
                connection.connect(new InetSocketAddress("127.0.0.1", port), 500);
                connection.setSoTimeout(2000);
                connection.setTcpNoDelay(true);
                var input = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
                while (!closed) {
                    var message = CompanionWire.read(input);
                    long received = System.nanoTime();
                    state.updateAndGet(current -> closed ? DisplayState.empty() : current.accept(message, received));
                }
            } catch (IOException error) {
                // The HUD disappears immediately; reconnect without producing an endless log.
            } finally {
                socket = null;
                state.set(DisplayState.empty());
            }
            try { Thread.sleep(500); }
            catch (InterruptedException interrupted) { break; }
        }
    }

    @Override public void close() {
        closed = true;
        Socket connection = socket;
        if (connection != null) try { connection.close(); } catch (IOException ignored) {}
        worker.interrupt();
        state.set(DisplayState.empty());
    }
}
