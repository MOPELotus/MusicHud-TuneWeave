package indi.mopelotus.musichud.client.update;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.jar.JarFile;

/** Standalone JDK-only helper. It never loads the running mod, and installs only after its owner exits. */
public final class UpdateInstaller {
    public static final String METADATA = "META-INF/musichud-update.properties";
    public record Plan(String oldFile, String newFile, String oldHash, String newHash,
                       String version, String distribution, String loader, String minecraft, long parent, String started) {}
    private UpdateInstaller() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IOException("Expected staging directory");
        Path stage = Path.of(args[0]).toAbsolutePath().normalize();
        noSymlinks(stage);
        if (stage.getParent() == null || !stage.getParent().getFileName().toString().equals(".musichud-tuneweave-updates")
                || !stage.getFileName().toString().matches("[0-9a-f-]{36}")) throw new IOException("Invalid staging location");
        try {
            Plan plan = read(stage.resolve("install.plan"));
            var parent = ProcessHandle.of(plan.parent());
            if (parent.isPresent()) {
                if (!parent.get().info().startInstant().map(Instant::toString).orElse("").equals(plan.started()))
                    throw new IOException("Parent process identity changed");
                parent.get().onExit().join();
            }
            install(stage, plan);
        } catch (Exception failure) {
            Files.writeString(stage.resolve("failed.txt"), failure.getClass().getSimpleName(), StandardOpenOption.CREATE_NEW);
            throw failure;
        }
    }

    public static void write(Path file, Plan p) throws IOException {
        try (var out = new DataOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
            out.writeInt(1);
            for (String s : new String[]{p.oldFile(), p.newFile(), p.oldHash(), p.newHash(), p.version(), p.distribution(), p.loader(), p.minecraft()}) out.writeUTF(s);
            out.writeLong(p.parent()); out.writeUTF(p.started());
        }
    }
    static Plan read(Path file) throws IOException {
        regular(file);
        if (Files.size(file) > 4096) throw new IOException("Oversized plan");
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 1) throw new IOException("Unknown plan");
            Plan p = new Plan(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readUTF());
            if (in.read() != -1) throw new IOException("Trailing plan bytes");
            return p;
        }
    }
    static void install(Path stage, Plan p) throws Exception {
        if (p.parent() <= 0) throw new IOException("Invalid parent");
        if (ProcessHandle.of(p.parent()).filter(ProcessHandle::isAlive).isPresent()) throw new IOException("Client still running");
        stage = stage.toAbsolutePath().normalize();
        Path managed = stage.getParent(), mods = managed == null ? null : managed.getParent();
        if (mods == null || !managed.getFileName().toString().equals(".musichud-tuneweave-updates")
                || !stage.getFileName().toString().matches("[0-9a-f-]{36}")) throw new IOException("Invalid staging location");
        noSymlinks(stage);
        if (!safeName(p.oldFile()) || !safeName(p.newFile()) || !p.oldHash().matches("[0-9a-f]{64}") || !p.newHash().matches("[0-9a-f]{64}")) throw new IOException("Invalid plan identity");
        Path old = mods.resolve(p.oldFile()), target = mods.resolve(p.newFile()), downloaded = stage.resolve("download.jar"), backup = stage.resolve("previous.jar");
        try (var channel = FileChannel.open(stage.resolve("install.lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            regular(old); regular(downloaded);
            if (!hash(old).equals(p.oldHash()) || !hash(downloaded).equals(p.newHash())) throw new IOException("JAR changed since download");
            verifyJar(old, null, p.distribution(), p.loader(), p.minecraft());
            verifyJar(downloaded, p.version(), p.distribution(), p.loader(), p.minecraft());
            // Existing targets and backups belong to the user; never overwrite either.
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) || !target.equals(old) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination already exists");
            Files.move(old, backup);
            try { Files.move(downloaded, target); }
            catch (IOException failure) {
                if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) Files.move(backup, old);
                throw failure;
            }
            Files.writeString(stage.resolve("installed.txt"), p.newFile(), StandardOpenOption.CREATE_NEW);
        }
    }
    public static void verifyJar(Path jar, String version, String distribution, String loader, String minecraft) throws IOException {
        regular(jar);
        try (var zip = new JarFile(jar.toFile(), true)) {
            var entry = zip.getJarEntry(METADATA);
            if (entry == null || entry.getSize() < 0 || entry.getSize() > 4096) throw new IOException("Missing update identity");
            Properties values = new Properties();
            try (var in = zip.getInputStream(entry)) { values.load(new ByteArrayInputStream(in.readNBytes(4097))); }
            if (!"musichud_tuneweave".equals(values.getProperty("id")) || version != null && !version.equals(values.getProperty("version"))
                    || !distribution.equals(values.getProperty("distribution")) || !loader.equals(values.getProperty("loader"))
                    || !java.util.List.of(values.getProperty("minecraft", "").split(",")).contains(minecraft)) throw new IOException("Downloaded JAR identity mismatch");
            if (zip.getJarEntry(loader.equals("fabric") ? "fabric.mod.json" : "META-INF/neoforge.mods.toml") == null) throw new IOException("Wrong loader");
        }
    }
    public static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; int n; while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static boolean safeName(String name) { return name.matches("[A-Za-z0-9_.+ -]{1,180}\\.jar") && !name.startsWith("."); }
    static void regular(Path path) throws IOException { if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file"); }
    public static void noSymlinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symlinked update path");
    }
}
