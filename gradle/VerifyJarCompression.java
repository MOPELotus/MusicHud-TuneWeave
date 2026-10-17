import java.io.*;
import java.nio.file.*;
import java.util.jar.JarInputStream;
import java.util.zip.*;

/** Checks the final archive independently of Commons Compress, using the build JVM's raw DEFLATE. */
class VerifyJarCompression {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one JAR");
        Path path = Path.of(args[0]);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("META-INF/MANIFEST.MF") != null) {
                try (JarInputStream jar = new JarInputStream(Files.newInputStream(path))) {
                    if (jar.getManifest() == null) throw new IOException("JAR manifest is not at the start");
                }
            }
            byte[] input = new byte[32768];
            byte[] output = new byte[32768];
            int checked = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getMethod() != ZipEntry.DEFLATED) {
                    throw new IOException("Entry is not DEFLATE-compressed: " + entry.getName());
                }
                Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
                long size = 0;
                try (InputStream stream = zip.getInputStream(entry)) {
                    for (int n; (n = stream.read(input)) != -1;) {
                        deflater.setInput(input, 0, n);
                        while (!deflater.needsInput()) size += deflater.deflate(output);
                    }
                    deflater.finish();
                    while (!deflater.finished()) size += deflater.deflate(output);
                } finally {
                    deflater.end();
                }
                if (size != entry.getCompressedSize()) {
                    throw new IOException("Entry does not match level 9: " + entry.getName() + " expected=" + size + " actual=" + entry.getCompressedSize());
                }
                checked++;
            }
            System.out.println("Verified maximum DEFLATE compression and manifest: " + path.getFileName()
                    + " (" + checked + " files)");
        }
    }
}
