package indi.mopelotus.musichud.client.companion;

import indi.mopelotus.musichud.MusicHud;
import net.minecraft.client.Minecraft;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standard edition only: owns children it launches, never attaches to or kills an external viewer. */
public final class CompanionDistribution {
    private static final State HOLDER = new State();
    private static final class State {
        final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool(Thread.ofVirtual().name("companion-manager-", 0).factory());
        final java.util.concurrent.ExecutorService lifecycle = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("companion-lifecycle-", 0).factory());
        final ManagedCompanionProcess process = new ManagedCompanionProcess(lifecycle);
        final Path root = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("musichud-tuneweave/companion");
        volatile String executable = "";
        volatile String version = "";
        volatile String installDirectory = "";
        volatile long downloaded, total;
        volatile String latest = "", manifestError = "";
        volatile boolean checking;
        volatile java.util.concurrent.CompletableFuture<Void> stopping = java.util.concurrent.CompletableFuture.completedFuture(null);
        State() {
            Properties properties = CompanionSettingsFile.load(root.resolve("companion.properties"));
            executable = properties.getProperty("executable", "");
            version = properties.getProperty("version", "");
            installDirectory = properties.getProperty("installDirectory", "");
        }
    }
    private CompanionDistribution() {}
    public static void enabled(boolean enabled) { if (enabled) start(); else stop(); }
    public static void shutdown() { HOLDER.process.close(); HOLDER.lifecycle.shutdown(); HOLDER.worker.shutdown(); }
    static boolean supported() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") && System.getProperty("os.arch", "").matches("(?i)(amd64|x86_64)"); }
    static Path directory() {
        try { return HOLDER.installDirectory.isBlank() ? HOLDER.root : Path.of(HOLDER.installDirectory).toAbsolutePath().normalize(); }
        catch (java.nio.file.InvalidPathException error) { return HOLDER.root; }
    }
    static synchronized boolean directory(String path) {
        try { if (!path.isBlank()) Path.of(path.trim()); }
        catch (java.nio.file.InvalidPathException error) { HOLDER.manifestError = "Invalid Companion installation directory"; return false; }
        if (HOLDER.installDirectory.equals(path.trim())) return true;
        if (HOLDER.process.busy()) HOLDER.process.stop();
        HOLDER.installDirectory = path.trim(); HOLDER.worker.execute(CompanionDistribution::save);
        return true;
    }
    static String executable() { return HOLDER.executable; }
    static String version() { return HOLDER.version; }
    static boolean busy() { return HOLDER.process.busy(); }
    static boolean running() { return HOLDER.process.running(); }
    static String failure() { return HOLDER.process.failure().isBlank() ? HOLDER.manifestError : HOLDER.process.failure(); }
    static String latestVersion() { return HOLDER.latest; }
    static boolean checking() { return HOLDER.checking; }
    static synchronized void checkLatest() {
        if (HOLDER.checking) return;
        HOLDER.checking = true;
        HOLDER.manifestError = "";
        HOLDER.worker.execute(() -> {
            try { HOLDER.latest = new CompanionReleaseInstaller().fetchLatest(new java.util.concurrent.atomic.AtomicBoolean()).version(); }
            catch (Exception error) { HOLDER.manifestError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
            finally { HOLDER.checking = false; }
        });
    }
    static long downloaded() { return HOLDER.downloaded; }
    static long total() { return HOLDER.total; }
    static synchronized void executable(String path) {
        if (HOLDER.executable.equals(path.trim())) return;
        if (HOLDER.process.busy()) HOLDER.process.stop();
        HOLDER.executable = path.trim();
        HOLDER.version = "";
        HOLDER.worker.execute(CompanionDistribution::save);
    }
    static void start() { start(false, false); }
    private static void start(boolean forceDownload, boolean replacingOwned) {
        if (!supported() || (!forceDownload && !replacingOwned && CompanionBridge.connected())) return;
        HOLDER.manifestError = "";
        HOLDER.process.start(cancelled -> {
            HOLDER.stopping.get(5, TimeUnit.SECONDS);
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            Path executable;
            String selected = HOLDER.executable;
            if (forceDownload || selected.isBlank()) {
                var installed = new CompanionReleaseInstaller().install(directory(), cancelled, (done, total) -> { HOLDER.downloaded = done; HOLDER.total = total; });
                executable = installed.executable();
                synchronized (CompanionDistribution.class) {
                    if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                    HOLDER.executable = executable.toString();
                    HOLDER.version = installed.version();
                    save();
                }
            } else executable = Path.of(selected).toAbsolutePath().normalize();
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            if (!Files.isRegularFile(executable) || !executable.getFileName().toString().equalsIgnoreCase("Companion.exe") || !Files.isDirectory(executable.getParent().resolve("runtime")) || !Files.isDirectory(executable.getParent().resolve("app")))
                throw new java.io.IOException("Select Companion.exe inside the complete Companion app directory");
            String childVersion = probeVersion(executable);
            Path identityPath = executable.getParent().resolve("companion.json");
            if (!Files.isRegularFile(identityPath) || Files.size(identityPath) > 4096) throw new java.io.IOException("Missing or invalid Companion identity");
            var identity = com.google.gson.JsonParser.parseString(Files.readString(identityPath)).getAsJsonObject();
            if (identity.get("schemaVersion").getAsInt() != 1 || identity.get("protocolVersion").getAsInt() != 1
                    || !identity.get("version").getAsString().equals(childVersion) || !managedVersion(childVersion))
                throw new java.io.IOException("Managed launch requires a compatible Companion 0.2.0 or newer");
            synchronized (CompanionDistribution.class) {
                if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                HOLDER.version = childVersion;
                save();
            }
            if (!CompanionBridge.enabled()) return null;
            // The old socket can outlive its child for a short time. Never launch a
            // second viewer while a separately started viewer still owns the connection.
            for (int i = 0; replacingOwned && CompanionBridge.connected() && i < 40 && !cancelled.get(); i++) Thread.sleep(50);
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            if (CompanionBridge.connected()) return null;
            Files.createDirectories(HOLDER.root.resolve("logs"));
            var owner = ProcessHandle.current();
            Path signalDirectory = Files.createTempDirectory(HOLDER.root.resolve("logs"), "owned-child-");
            Path signal = signalDirectory.resolve("stop.signal");
            Process child;
            try {
                child = new ProcessBuilder(executable.toString(), "--owner-pid", Long.toString(owner.pid()), "--owner-start", Long.toString(owner.info().startInstant().orElseThrow().toEpochMilli()), "--stop-file", signal.toString())
                    .directory(executable.getParent().toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(HOLDER.root.resolve("logs/launcher.log").toFile())).start();
            } catch (Exception error) {
                Files.deleteIfExists(signalDirectory);
                throw error;
            }
            child.onExit().thenRun(() -> {
                try { Files.deleteIfExists(signal); Files.deleteIfExists(signalDirectory); } catch (java.io.IOException ignored) {}
            });
            return new ManagedCompanionProcess.Child() {
                public boolean alive() { return child.isAlive(); }
                public void stop() {
                    try { Files.createFile(signal); } catch (java.io.IOException ignored) {}
                    HOLDER.stopping = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try { if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); } }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
                        finally {
                            try { Files.deleteIfExists(signal); Files.deleteIfExists(signalDirectory); } catch (java.io.IOException ignored) {}
                        }
                    }, command -> Thread.startVirtualThread(command));
                }
            };
        });
    }
    static void stop() { HOLDER.process.stop(); }
    static void restart() { boolean owned = HOLDER.process.running() || HOLDER.process.busy(); stop(); if (CompanionBridge.enabled()) start(false, owned); }
    static void download() {
        if (!supported()) return;
        boolean owned = HOLDER.process.running() || HOLDER.process.busy();
        if (owned) stop();
        start(true, owned);
    }
    private static boolean managedVersion(String version) {
        if (!version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) return false;
        String[] parts = version.split("\\.");
        try { return Integer.parseInt(parts[0]) > 0 || Integer.parseInt(parts[1]) >= 2; }
        catch (NumberFormatException error) { return false; }
    }
    private static String probeVersion(Path executable) throws Exception {
        Path log = Files.createTempFile("musichud-companion-version-", ".txt");
        Process probe = null;
        try {
            probe = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!probe.waitFor(5, TimeUnit.SECONDS)) throw new java.io.IOException("Companion version check timed out");
            if (probe.exitValue() != 0 || Files.size(log) > 256) throw new java.io.IOException("Invalid Companion version response");
            return Files.readString(log).trim();
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
    private static synchronized void save() {
        Path temp = null;
        try {
            Files.createDirectories(HOLDER.root);
            temp = Files.createTempFile(HOLDER.root, ".companion-", ".tmp");
            Properties properties = new Properties();
            properties.setProperty("executable", HOLDER.executable);
            properties.setProperty("version", HOLDER.version);
            properties.setProperty("installDirectory", HOLDER.installDirectory);
            try (OutputStream out = Files.newOutputStream(temp)) { properties.store(out, "Companion executable selection; no credentials"); }
            try { Files.move(temp, HOLDER.root.resolve("companion.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, HOLDER.root.resolve("companion.properties"), StandardCopyOption.REPLACE_EXISTING); }
        } catch (java.io.IOException error) { MusicHud.LOGGER.warn("Cannot save Companion settings: {}", error.getMessage()); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (java.io.IOException ignored) {} }
    }
}
