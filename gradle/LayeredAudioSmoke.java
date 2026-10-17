import java.io.File;
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Verifies the packaged audio path with game libraries represented as named JPMS modules. */
// Arguments: existing smoke source, work directory, distribution, final JAR, host library JARs.
class LayeredAudioSmoke {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        Files.createDirectories(Path.of(args[1]));
        Path work = Files.createTempDirectory(Path.of(args[1]), "modules-");
        String distribution = args[2];
        List<Path> jars = Arrays.stream(args).skip(3).map(Path::of).toList();
        Files.createDirectories(work.resolve("source/audit"));
        Path testSource = work.resolve("source/audit/CfAudioSmoke.java");
        Files.writeString(testSource, "package audit;\n" + Files.readString(source));
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        String classpath = String.join(File.pathSeparator, jars.stream().map(Path::toString).toList());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-cp", classpath, "-d", classes.toString(), testSource.toString());
        if (exit != 0) throw new IllegalStateException("Audio smoke compilation failed");
        Path testJar = work.resolve("audio-module-smoke.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "musichud.verification");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(testJar), manifest);
             var files = Files.walk(classes)) {
            output.setLevel(9);
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        List<Path> modulePath = new ArrayList<>(jars);
        modulePath.add(testJar);
        ModuleFinder finder = ModuleFinder.of(modulePath.toArray(Path[]::new));
        Set<String> roots = new TreeSet<>();
        finder.findAll().forEach(module -> roots.add(module.descriptor().name()));
        var configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), roots);
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
        ClassLoader loader = layer.findLoader("musichud.verification");
        Thread.currentThread().setContextClassLoader(loader);
        var main = loader.loadClass("audit.CfAudioSmoke").getDeclaredMethod("main", String[].class);
        main.setAccessible(true);
        main.invoke(null, (Object) new String[] {distribution});
        Path wave = work.resolve("production-adapter.wav");
        byte[] pcm = new byte[4800 * 4];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 100);
        var format = new javax.sound.sampled.AudioFormat(48000, 16, 2, true, false);
        try (var input = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm), format, 4800)) {
            javax.sound.sampled.AudioSystem.write(input, javax.sound.sampled.AudioFileFormat.Type.WAVE, wave.toFile());
        }
        Class<?> adapter = loader.loadClass("indi.mopelotus.musichud.client.audio.decoder.LavaplayerStreamDecoder");
        Object decoder = adapter.getMethod("open", String.class).invoke(null, wave.toString());
        try (AutoCloseable closeable = (AutoCloseable) decoder) {
            byte[] decoded = (byte[]) adapter.getMethod("readChunk", long.class).invoke(decoder, 16384L);
            if (decoded == null || decoded.length == 0) throw new IllegalStateException("Production adapter returned no PCM");
        }
        System.out.println("Verified packaged audio in named modules: " + roots);
    }
}
