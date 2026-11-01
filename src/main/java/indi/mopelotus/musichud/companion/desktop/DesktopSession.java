package indi.mopelotus.musichud.companion.desktop;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Prevents reconnect contention between duplicate viewers and keeps packaged-launcher diagnostics. */
final class DesktopSession implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final PrintStream originalOut = System.out, originalErr = System.err;
    private final PrintStream log;

    DesktopSession(Path directory, int port) throws IOException {
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("companion-" + port + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException duplicate) { throw new IOException("Companion is already running for port " + port); }
            if (lock == null) throw new IOException("Companion is already running for port " + port);
            Path file = directory.resolve("companion-" + port + ".log");
            if (Files.exists(file) && Files.size(file) > 1024 * 1024) {
                Files.move(file, directory.resolve("companion-" + port + ".previous.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            log = new PrintStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) { channel.close(); throw error; }
        System.setOut(new PrintStream(new Tee(originalOut, log).stream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(originalErr, log).stream(), true, StandardCharsets.UTF_8));
    }

    private record Tee(PrintStream console, PrintStream file) {
        private OutputStream stream() {
            return new OutputStream() {
                @Override public void write(int value) { console.write(value); file.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) { console.write(bytes, offset, length); file.write(bytes, offset, length); }
                @Override public void flush() { console.flush(); file.flush(); }
            };
        }
    }

    @Override public void close() throws IOException {
        System.out.flush(); System.err.flush();
        System.setOut(originalOut); System.setErr(originalErr);
        log.close();
        try { lock.release(); } finally { channel.close(); }
    }
}
