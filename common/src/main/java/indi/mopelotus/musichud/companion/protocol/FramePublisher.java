package indi.mopelotus.musichud.companion.protocol;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/** One display peer, bounded latest-frame mailbox, and no application messages from the peer. */
public final class FramePublisher implements AutoCloseable {
    public record Capture(long generation, long epoch, long sequence, long capturedAtMillis) {}
    private record Pixels(Capture capture, int width, int height, byte[] rgba) {}
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private final ServerSocket listener;
    private volatile boolean closed;
    private Peer peer;
    private Pixels pixels;
    private long epoch, captureSequence, publishedSequence = -1, revision;
    private boolean locked = true;

    private static final class Peer {
        final Socket socket;
        final long generation = GENERATIONS.incrementAndGet();
        volatile long writingSince;
        Peer(Socket socket) { this.socket = socket; }
        void close() { try { socket.close(); } catch (IOException ignored) {} }
    }

    public FramePublisher(int port) throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1);
        listener.setSoTimeout(250);
        Thread.ofVirtual().name("companion-accept").start(this::accept);
    }

    public int port() { return listener.getLocalPort(); }
    public synchronized boolean connected() { return peer != null && !closed; }
    public synchronized boolean locked() { return locked; }
    public synchronized long generation() { return peer == null || closed ? 0 : peer.generation; }

    /** A queued settings action must never unlock a replacement connection. */
    public synchronized void setLocked(long generation, boolean locked) {
        if (peer != null && !closed && peer.generation == generation) setLocked(locked);
    }

    public synchronized void setLocked(boolean locked) {
        this.locked = peer == null || locked;
        revision++;
        notifyAll();
    }

    public synchronized Capture capture() {
        return peer == null || closed ? null : new Capture(peer.generation, epoch, ++captureSequence, System.currentTimeMillis());
    }

    /** Ownership of rgba transfers to this mailbox; the caller must not mutate it afterward. */
    public synchronized void publish(Capture capture, int width, int height, byte[] rgba) {
        if (closed || peer == null || capture.generation != peer.generation || capture.epoch != epoch
                || capture.sequence <= publishedSequence) return;
        if (rgba.length != CompanionWire.pixelBytes(width, height)) throw new IllegalArgumentException("Invalid pixels");
        pixels = new Pixels(capture, width, height, rgba);
        publishedSequence = capture.sequence;
        revision++;
        notifyAll();
    }

    public synchronized void clear() {
        epoch++;
        pixels = null;
        revision++;
        notifyAll();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket connection = listener.accept();
                connection.setTcpNoDelay(true);
                Peer next = new Peer(connection);
                synchronized (this) {
                    if (closed) { next.close(); return; }
                    if (peer != null) peer.close();
                    peer = next;
                    locked = true;
                    pixels = null;
                    epoch++;
                    revision++;
                    notifyAll();
                }
                Thread.ofVirtual().name("companion-send").start(() -> send(next));
            } catch (SocketTimeoutException timeout) {
                synchronized (this) {
                    if (peer != null && peer.writingSince != 0 && System.nanoTime() - peer.writingSince > 2_000_000_000L) peer.close();
                }
            } catch (IOException error) {
                if (!closed) close();
            }
        }
    }

    private void send(Peer owner) {
        long seenRevision = -1, wireSequence = 0;
        Pixels sent = null;
        try (var output = new DataOutputStream(new BufferedOutputStream(owner.socket.getOutputStream()))) {
            while (!closed) {
                CompanionWire.Message message;
                synchronized (this) {
                    if (peer != owner) break;
                    if (seenRevision == revision) wait(500);
                    if (closed || peer != owner) break;
                    Pixels current = pixels;
                    var kind = current == null ? CompanionWire.Kind.CLEAR
                            : current != sent ? CompanionWire.Kind.FRAME : CompanionWire.Kind.STATUS;
                    message = new CompanionWire.Message(kind, owner.generation, wireSequence++,
                            kind == CompanionWire.Kind.FRAME ? current.capture.capturedAtMillis : System.currentTimeMillis(),
                            locked, kind == CompanionWire.Kind.FRAME ? current.width : 0,
                            kind == CompanionWire.Kind.FRAME ? current.height : 0,
                            kind == CompanionWire.Kind.FRAME ? current.rgba : new byte[0]);
                    sent = current;
                    seenRevision = revision;
                }
                owner.writingSince = System.nanoTime();
                CompanionWire.write(output, message);
                output.flush();
                owner.writingSince = 0;
            }
        } catch (IOException ignored) {
            // Losing the display peer never affects playback or the game render thread.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            owner.close();
            synchronized (this) {
                if (peer == owner) {
                    peer = null;
                    pixels = null;
                    epoch++;
                    locked = true;
                }
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (peer != null) peer.close();
        peer = null;
        pixels = null;
        epoch++;
        locked = true;
        notifyAll();
        try { listener.close(); } catch (IOException ignored) {}
    }
}
