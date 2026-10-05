package indi.mopelotus.musichud.client.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

/** A successful install is disposable only after the matching JAR reaches a usable client screen. */
final class UpdateBackupCleanup {
    private static final List<String> FILES = List.of("previous.jar", "installer.jar", "installer.log", "install.lock", "installed.txt", "install.plan");
    private UpdateBackupCleanup() {}

    static int clean(Path activeJar, ClientUpdateCatalog.Installed installed) throws IOException {
        Path active = activeJar.toAbsolutePath().normalize();
        UpdateInstaller.noSymlinks(active);
        UpdateInstaller.regular(active);
        if (!active.getParent().getFileName().toString().equals("mods")) return 0;
        Path root = active.getParent().resolve(".musichud-tuneweave-updates");
        UpdateInstaller.noSymlinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        UpdateInstaller.verifyJar(active, installed.version(), installed.distribution(), installed.loader(), installed.minecraft());
        String activeHash = UpdateInstaller.hash(active);
        int removed = 0;
        try (var children = Files.list(root)) {
            for (Path stage : children.toList()) {
                try { if (cleanStage(stage, active, installed, activeHash)) removed++; }
                catch (IOException | RuntimeException ignored) { /* Keep incomplete or unrecognized records for inspection. */ }
            }
        }
        // Never recurse through unknown entries or delete another pending update.
        try { Files.delete(root); } catch (DirectoryNotEmptyException ignored) { }
        return removed;
    }

    private static boolean cleanStage(Path stage, Path active, ClientUpdateCatalog.Installed installed, String activeHash) throws IOException {
        if (!stage.getFileName().toString().matches("[0-9a-f-]{36}") || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) return false;
        UpdateInstaller.noSymlinks(stage);
        try (var files = Files.list(stage)) {
            for (Path file : files.toList()) {
                if (!FILES.contains(file.getFileName().toString()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSameFile(file, active)) return false;
            }
        }
        Path marker = stage.resolve("installed.txt");
        UpdateInstaller.regular(marker);
        if (Files.size(marker) > 256) return false;
        var plan = UpdateInstaller.read(stage.resolve("install.plan"));
        if (!plan.version().equals(installed.version()) || !plan.distribution().equals(installed.distribution())
                || !plan.loader().equals(installed.loader()) || !plan.minecraft().equals(installed.minecraft())
                || !plan.newFile().equals(active.getFileName().toString()) || !plan.newHash().equals(activeHash)
                || !Files.readString(marker).equals(plan.newFile()) || plan.parent() <= 0
                || ProcessHandle.of(plan.parent()).filter(ProcessHandle::isAlive).isPresent()) return false;
        Path backup = stage.resolve("previous.jar");
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !UpdateInstaller.hash(backup).equals(plan.oldHash())) return false;
        Path lockPath = stage.resolve("install.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) return false;
            }
        }
        // Recheck identity immediately before deletion. Only direct, known files are eligible.
        UpdateInstaller.noSymlinks(stage);
        for (String name : FILES) {
            Path file = stage.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                UpdateInstaller.regular(file);
                if (Files.isSameFile(file, active)) throw new IOException("Active JAR cannot be cleaned");
                Files.delete(file);
            }
        }
        Files.delete(stage);
        return true;
    }
}
