package indi.mopelotus.musichud.client.update;

import indi.mopelotus.musichud.MusicHud;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Standard-edition installation code is omitted entirely from CF artifacts. */
final class UpdateInstallation {
    private UpdateInstallation() {}
    static boolean enabled() { return true; }
    static void clean(Path jar, ClientUpdateCatalog.Installed installed) throws Exception {
        int removed = UpdateBackupCleanup.clean(jar, installed);
        if (removed > 0) MusicHud.LOGGER.info("Removed {} confirmed update backup directory/directories", removed);
    }
    static void stage(Path jar, ClientUpdateCatalog.Installed installed, ClientUpdateCatalog.Offer offer) throws Exception {
        Path active = jar.toAbsolutePath().normalize();
        UpdateInstaller.noSymlinks(active); UpdateInstaller.regular(active);
        if (!active.getParent().getFileName().toString().equals("mods") || !UpdateInstaller.safeName(active.getFileName().toString())) throw new IOException("Installation is not a regular mods JAR");
        UpdateInstaller.verifyJar(active, installed.version(), installed.distribution(), installed.loader(), installed.minecraft());
        Path managed = active.getParent().resolve(".musichud-tuneweave-updates");
        UpdateInstaller.noSymlinks(managed); Files.createDirectories(managed);
        // A staged update remains owned by its helper across UI retries and client restarts.
        try (var children = Files.list(managed)) {
            for (Path child : children.toList()) if (Files.exists(child.resolve("install.plan")) && !Files.exists(child.resolve("installed.txt")) && !Files.exists(child.resolve("failed.txt"))) throw new IOException("Pending update already exists");
        }
        Path stage = Files.createDirectory(managed.resolve(UUID.randomUUID().toString()));
        Path download = stage.resolve("download.jar");
        try (var out = Files.newOutputStream(download, StandardOpenOption.CREATE_NEW)) { ClientUpdateService.transfer(offer.downloadUri(), out, offer.size()); }
        if (Files.size(download) != offer.size() || !UpdateInstaller.hash(download).equals(offer.sha256())) throw new IOException("Downloaded checksum mismatch");
        UpdateInstaller.verifyJar(download, offer.version(), installed.distribution(), installed.loader(), installed.minecraft());
        Path helper = stage.resolve("installer.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(helper, StandardOpenOption.CREATE_NEW))) {
            for (Class<?> type : List.of(UpdateInstaller.class, UpdateInstaller.Plan.class)) {
                String name = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(name));
                try (var input = type.getClassLoader().getResourceAsStream(name)) {
                    if (input == null) throw new IOException("Installer unavailable"); input.transferTo(out);
                }
                out.closeEntry();
            }
        }
        var parent = ProcessHandle.current();
        var plan = new UpdateInstaller.Plan(active.getFileName().toString(), offer.file(), UpdateInstaller.hash(active), offer.sha256(), offer.version(), installed.distribution(), installed.loader(), installed.minecraft(), parent.pid(), parent.info().startInstant().orElseThrow().toString());
        UpdateInstaller.write(stage.resolve("install.plan"), plan);
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process;
        try {
            process = new ProcessBuilder(javaExecutable.toString(), "-cp", helper.toString(), UpdateInstaller.class.getName(), stage.toString())
                    .redirectErrorStream(true).redirectOutput(stage.resolve("installer.log").toFile()).start();
        } catch (IOException failure) {
            Files.writeString(stage.resolve("failed.txt"), "Installer could not start", StandardOpenOption.CREATE_NEW);
            throw failure;
        }
        if (process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (!Files.exists(stage.resolve("failed.txt"))) Files.writeString(stage.resolve("failed.txt"), "Installer exited before client", StandardOpenOption.CREATE_NEW);
            throw new IOException("Installer exited before client");
        }
    }
}
