package indi.mopelotus.musichud.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientUpdateTest {
    @TempDir Path temp;
    private static final String HASH = "a".repeat(64);
    private static ClientUpdateCatalog.Installed installed(String edition) {
        return new ClientUpdateCatalog.Installed(edition.equals("cf") ? "1.3.0-cf.1+26.3" : "1.3.0+26.3", edition, "fabric", "26.3");
    }
    private static String catalog(String edition) {
        String version = "1.4.0-beta.1" + (edition.equals("cf") ? "-cf.1" : "") + "+26.3";
        return """
                {"schema":1,"repository":"MOPELotus/MusicHud-TuneWeave","tag":"v1.4.0-beta.1","distribution":"%s",
                "artifacts":[{"version":"%s","distribution":"%s","loader":"fabric","minecraft":["26.3"],
                "file":"musichud-tuneweave-fabric-%s.jar","sha256":"%s","size":123}]}
                """.formatted(edition, version, edition, version, HASH);
    }
    private Path jar(Path path, String version, String edition, String loader) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
            out.putNextEntry(new JarEntry(UpdateInstaller.METADATA));
            out.write(("id=musichud_tuneweave\nversion=" + version + "\ndistribution=" + edition + "\nloader=" + loader + "\nminecraft=26.3\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry(); out.putNextEntry(new JarEntry(loader.equals("fabric") ? "fabric.mod.json" : "META-INF/neoforge.mods.toml")); out.write("{}".getBytes()); out.closeEntry();
        }
        return path;
    }
    private Path staging() throws Exception { return Files.createDirectories(temp.resolve("mods/.musichud-tuneweave-updates/" + UUID.randomUUID())); }
    private UpdateInstaller.Plan plan(Path stage, long parent) throws Exception {
        Path old = jar(temp.resolve("mods/old.jar"), "1.3.0+26.3", "standard", "fabric");
        Path next = jar(stage.resolve("download.jar"), "1.4.0+26.3", "standard", "fabric");
        return new UpdateInstaller.Plan("old.jar", "new.jar", UpdateInstaller.hash(old), UpdateInstaller.hash(next), "1.4.0+26.3", "standard", "fabric", "26.3", parent, "unused");
    }
    @Test void activeProcessAndUnrelatedFilesAreNeverChanged() throws Exception {
        Path stage = staging(); var p = plan(stage, ProcessHandle.current().pid());
        Path unrelated = Files.writeString(temp.resolve("mods/unrelated.jar"), "keep");
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, p));
        assertEquals(p.oldHash(), UpdateInstaller.hash(temp.resolve("mods/old.jar")));
        assertEquals("keep", Files.readString(unrelated)); assertFalse(Files.exists(stage.resolve("previous.jar")));
    }
    @Test void exitedProcessInstallsWithBackupAndPreservesUnrelatedFiles() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE);
        Files.writeString(temp.resolve("mods/unrelated.jar"), "keep");
        UpdateInstaller.install(stage, p);
        assertEquals(p.newHash(), UpdateInstaller.hash(temp.resolve("mods/new.jar")));
        assertEquals(p.oldHash(), UpdateInstaller.hash(stage.resolve("previous.jar")));
        assertEquals("keep", Files.readString(temp.resolve("mods/unrelated.jar")));
        assertFalse(Files.exists(temp.resolve("mods/old.jar")));
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, p));
    }
    @Test void existingTargetIsNotOverwritten() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE);
        Files.writeString(temp.resolve("mods/new.jar"), "user file");
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, p));
        assertEquals("user file", Files.readString(temp.resolve("mods/new.jar")));
        assertEquals(p.oldHash(), UpdateInstaller.hash(temp.resolve("mods/old.jar")));
    }
    @Test void changedDownloadAndWrongJarIdentityAreRejected() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE);
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.verifyJar(stage.resolve("download.jar"), p.version(), "cf", "fabric", "26.3"));
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.verifyJar(stage.resolve("download.jar"), p.version(), "standard", "neoforge", "26.3"));
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.verifyJar(stage.resolve("download.jar"), p.version(), "standard", "fabric", "26.2"));
        Files.writeString(stage.resolve("download.jar"), "broken");
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, p));
        assertEquals(p.oldHash(), UpdateInstaller.hash(temp.resolve("mods/old.jar")));
    }
    @Test void symlinksAndPathTraversalAreRejected() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE);
        var traversal = new UpdateInstaller.Plan("../outside.jar", p.newFile(), p.oldHash(), p.newHash(), p.version(), p.distribution(), p.loader(), p.minecraft(), p.parent(), p.started());
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, traversal));
        Path outside = Files.writeString(temp.resolve("outside.jar"), "keep");
        Files.createSymbolicLink(temp.resolve("mods/new.jar"), outside);
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.install(stage, p));
        assertEquals("keep", Files.readString(outside));
    }
    @Test void duplicateClicksAndLateUnsubscribeDoNotStartAnotherOperation() throws Exception {
        List<Runnable> work = new ArrayList<>(); List<ClientUpdateService.State> observed = new ArrayList<>(); int[] downloads = {0};
        var offer = ClientUpdateCatalog.select(catalog("standard"), "v1.4.0-beta.1", installed("standard"), true, "").orElseThrow();
        var service = new ClientUpdateService(work::add, new ClientUpdateService.Backend() {
            public Optional<ClientUpdateCatalog.Offer> check() { return Optional.of(offer); }
            public void stage(ClientUpdateCatalog.Offer value) { downloads[0]++; }
        });
        var subscription = service.subscribe(observed::add);
        service.check(); service.check(); assertEquals(1, work.size());
        subscription.close(); work.removeFirst().run(); assertEquals(1, observed.size());
        service.download(); service.download(); service.check(); assertEquals(1, work.size());
        work.removeFirst().run(); assertEquals(1, downloads[0]); assertEquals(ClientUpdateService.Status.READY, service.state().status());
        service.check(); service.download(); assertTrue(work.isEmpty());
    }
    @Test void planSerializationIsBoundedAndRoundTrips() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE); Path file = stage.resolve("install.plan");
        UpdateInstaller.write(file, p); assertEquals(p, UpdateInstaller.read(file));
        Files.writeString(file, "junk", StandardOpenOption.APPEND);
        assertThrows(java.io.IOException.class, () -> UpdateInstaller.read(file));
    }
    public static final class OwnerProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "ready");
            Thread.sleep(2500);
        }
    }
    @Test void separateHelperActuallyWaitsForClientExit() throws Exception {
        Path stage = staging();
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String tests = Path.of(ClientUpdateTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String main = Path.of(UpdateInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path ready = temp.resolve("owner-ready");
        Process owner = new ProcessBuilder(executable, "-cp", tests, OwnerProcess.class.getName(), ready.toString()).start();
        Process helper = null;
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(ready));
            var initial = plan(stage, owner.pid());
            var p = new UpdateInstaller.Plan(initial.oldFile(), initial.newFile(), initial.oldHash(), initial.newHash(), initial.version(), initial.distribution(), initial.loader(), initial.minecraft(), owner.pid(), owner.info().startInstant().orElseThrow().toString());
            UpdateInstaller.write(stage.resolve("install.plan"), p);
            helper = new ProcessBuilder(executable, "-cp", main, UpdateInstaller.class.getName(), stage.toString()).redirectErrorStream(true).redirectOutput(stage.resolve("helper.log").toFile()).start();
            Thread.sleep(300);
            assertTrue(owner.isAlive()); assertTrue(helper.isAlive());
            assertEquals(p.oldHash(), UpdateInstaller.hash(temp.resolve("mods/old.jar")));
            assertTrue(helper.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), Files.readString(stage.resolve("helper.log")));
            assertEquals(p.newHash(), UpdateInstaller.hash(temp.resolve("mods/new.jar")));
            assertEquals(p.oldHash(), UpdateInstaller.hash(stage.resolve("previous.jar")));
        } finally {
            owner.destroyForcibly(); if (helper != null && helper.isAlive()) helper.destroyForcibly();
        }
    }

    private Path installedStage() throws Exception {
        Path stage = staging(); var p = plan(stage, Long.MAX_VALUE);
        UpdateInstaller.write(stage.resolve("install.plan"), p);
        UpdateInstaller.install(stage, p);
        return stage;
    }
    private ClientUpdateCatalog.Installed nextInstalled() {
        return new ClientUpdateCatalog.Installed("1.4.0+26.3", "standard", "fabric", "26.3");
    }
    @Test void confirmedStartupDeletesOwnedDirectoryAndEmptyRootOnly() throws Exception {
        Path stage = installedStage(), active = temp.resolve("mods/new.jar");
        String hash = UpdateInstaller.hash(active);
        Files.writeString(temp.resolve("mods/unrelated.jar"), "user");
        Files.writeString(stage.resolve("installer.log"), "completed");
        assertEquals(1, UpdateBackupCleanup.clean(active, nextInstalled()));
        assertFalse(Files.exists(stage.getParent()));
        assertEquals(hash, UpdateInstaller.hash(active));
        assertEquals("user", Files.readString(temp.resolve("mods/unrelated.jar")));
        assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
    }
    @Test void pendingAndFailedUpdatesAndUnknownRootFilesRemain() throws Exception {
        Path stage = installedStage(), pending = Files.createDirectory(stage.getParent().resolve(UUID.randomUUID().toString()));
        Files.writeString(pending.resolve("download.jar"), "pending");
        Path unrelated = Files.writeString(stage.getParent().resolve("my-notes.txt"), "user");
        assertEquals(1, UpdateBackupCleanup.clean(temp.resolve("mods/new.jar"), nextInstalled()));
        assertEquals("pending", Files.readString(pending.resolve("download.jar")));
        assertEquals("user", Files.readString(unrelated));
    }
    @Test void unknownFilesOrFailureMarkerPreventAnyDeletionInSession() throws Exception {
        Path stage = installedStage(); Path backup = stage.resolve("previous.jar");
        String hash = UpdateInstaller.hash(backup);
        Files.writeString(stage.resolve("failed.txt"), "failed");
        assertEquals(0, UpdateBackupCleanup.clean(temp.resolve("mods/new.jar"), nextInstalled()));
        assertEquals(hash, UpdateInstaller.hash(backup));
        Files.delete(stage.resolve("failed.txt"));
        Files.writeString(stage.resolve("user.txt"), "keep");
        assertEquals(0, UpdateBackupCleanup.clean(temp.resolve("mods/new.jar"), nextInstalled()));
        assertEquals("keep", Files.readString(stage.resolve("user.txt")));
        assertEquals(hash, UpdateInstaller.hash(backup));
    }
    @Test void symlinkAndActiveHardLinkAreNotDeleted() throws Exception {
        Path stage = installedStage(), active = temp.resolve("mods/new.jar"), outside = Files.writeString(temp.resolve("outside.txt"), "keep");
        Files.createSymbolicLink(stage.resolve("installer.log"), outside);
        assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
        assertEquals("keep", Files.readString(outside));
        Files.delete(stage.resolve("installer.log"));
        Files.createLink(stage.resolve("installer.jar"), active);
        assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
        assertTrue(Files.exists(stage.resolve("previous.jar")));
        assertTrue(Files.exists(active));
    }
    @Test void changedBackupAndRunningInstallerAreRetained() throws Exception {
        Path stage = installedStage(), active = temp.resolve("mods/new.jar");
        try (var channel = java.nio.channels.FileChannel.open(stage.resolve("install.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
            assertTrue(Files.exists(stage.resolve("previous.jar")));
        }
        Files.writeString(stage.resolve("previous.jar"), "unrelated replacement");
        assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
        assertEquals("unrelated replacement", Files.readString(stage.resolve("previous.jar")));
    }
    @Test void missingSuccessMarkerOrDifferentActiveJarCannotAcknowledgeUpdate() throws Exception {
        Path stage = installedStage(), active = temp.resolve("mods/new.jar");
        Files.delete(stage.resolve("installed.txt"));
        assertEquals(0, UpdateBackupCleanup.clean(active, nextInstalled()));
        assertTrue(Files.exists(stage.resolve("previous.jar")));
        Files.writeString(stage.resolve("installed.txt"), "new.jar");
        Path another = temp.resolve("mods/renamed.jar"); Files.copy(active, another);
        assertEquals(0, UpdateBackupCleanup.clean(another, nextInstalled()));
        assertTrue(Files.exists(stage.resolve("previous.jar")));
        Files.delete(stage.resolve("previous.jar")); // Simulate interrupted cleanup after deleting the backup.
        assertEquals(1, UpdateBackupCleanup.clean(active, nextInstalled()));
    }
    @Test void startupConfirmationSchedulesCleanupOnceEvenWithLateCallbacks() {
        List<Runnable> work = new ArrayList<>(); int[] cleaned = {0};
        var service = new ClientUpdateService(work::add, new ClientUpdateService.Backend() {
            public Optional<ClientUpdateCatalog.Offer> check() { return Optional.empty(); }
            public void stage(ClientUpdateCatalog.Offer offer) {}
            public void cleanSuccessfulUpdate() { cleaned[0]++; }
        });
        assertTrue(work.isEmpty());
        service.clientStartedSuccessfully(); service.clientStartedSuccessfully();
        assertEquals(1, work.size()); assertEquals(0, cleaned[0]);
        work.removeFirst().run(); service.clientStartedSuccessfully();
        assertEquals(1, cleaned[0]); assertTrue(work.isEmpty());
    }

}
