package indi.mopelotus.musichud.metadata;

import java.nio.file.Path;

/** Forked test entrypoint; the runtime classpath contains the actual shrunk component. */
public final class MetadataComponentProbe {
    public static void main(String[] files) {
        for (String file : files) {
            var metadata = AudioMetadataReader.read(Path.of(file));
            System.out.println(Path.of(file).getFileName() + "\t" + (metadata.durationMillis() != null));
        }
    }
}
