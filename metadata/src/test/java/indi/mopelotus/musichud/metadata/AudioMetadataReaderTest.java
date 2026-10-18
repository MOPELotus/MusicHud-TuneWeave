package indi.mopelotus.musichud.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class AudioMetadataReaderTest {
    @TempDir Path temporary;
    private static final String[] EXTENSIONS = {
            "ogg", "oga", "flac", "mp3", "mp4", "m4a", "m4p", "m4b", "wav", "wma",
            "aif", "aifc", "aiff", "dsf", "dff", "ra", "rm"
    };
    private record Snapshot(String title, String artist, String album, String cover, Integer duration, Long bitrate) {}

    @Test void packagedMetadataMatchesOriginalReadersAcrossFormatsAndMalformedTags() throws Exception {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("tagged-v23.mp3", "tagged-v24.mp3", "tagged.flac", "tagged-aac.m4a",
                "tagged-alac.m4a", "tagged-vorbis.ogg", "tagged-opus.ogg", "tagged.wav", "tagged.aiff",
                "tagged.wma", "tagged.rm", "jpeg.flac")) {
            Path file = fixture(name); files.add(file);
            byte[] bytes = Files.readAllBytes(file);
            for (int length : new int[]{100, Math.min(1024, bytes.length), bytes.length / 2}) {
                files.add(write("truncated-" + length + "-" + name, Arrays.copyOf(bytes, length)));
            }
        }
        Map<String, String> aliases = Map.of("oga", "tagged-vorbis.ogg", "mp4", "tagged-aac.m4a",
                "m4p", "tagged-aac.m4a", "m4b", "tagged-alac.m4a", "aif", "tagged.aiff",
                "aifc", "tagged.aiff", "ra", "tagged.rm", "MP3", "tagged-v23.mp3");
        for (var alias : aliases.entrySet()) files.add(write("alias." + alias.getKey(), resource(alias.getValue())));
        files.add(write("tagged.dsf", dsf(resource("tagged-v23.mp3"))));
        files.add(write("even.dff", dff("not compressed ", new byte[0], new byte[0], new byte[0])));
        for (String extension : EXTENSIONS) {
            files.add(write("tiny." + extension, new byte[]{1, 2, 3}));
            byte[] invalid = new byte[1024]; for (int i = 0; i < invalid.length; i++) invalid[i] = (byte) i;
            files.add(write("random." + extension, invalid));
            files.add(write("empty." + extension, new byte[0]));
        }
        files.add(temporary.resolve("missing.mp3"));
        files.add(write("unsupported.bin", new byte[]{1, 2, 3}));
        try (var original = isolated(Path.of(System.getProperty("musichud.metadata.original")));
             var component = isolated(Path.of(System.getProperty("musichud.metadata.component")))) {
            for (Path file : files) assertEquals(baseline(original, file), candidate(component, file), file.getFileName().toString());
            var tagged = candidate(component, temporary.resolve("tagged-v23.mp3"));
            assertEquals("测试歌名 🎵 / A&B", tagged.title());
            assertEquals("歌手甲 / Artist β", tagged.artist());
            assertEquals("专辑 😀 & <demo>", tagged.album());
            assertTrue(tagged.cover().startsWith("data:image/png;base64,"));
            assertTrue(tagged.duration() > 0);
            assertTrue(tagged.bitrate() > 0);
            assertTrue(candidate(component, temporary.resolve("jpeg.flac")).cover().startsWith("data:image/jpeg;base64,"));
        }
    }

    @Test void artworkLimitsMimeAndExactBytesMatchOriginalUploadContract() throws Exception {
        byte[] flac = resource("tagged.flac");
        try (var original = isolated(Path.of(System.getProperty("musichud.metadata.original")));
             var component = isolated(Path.of(System.getProperty("musichud.metadata.component")))) {
            for (int length : new int[]{8 * 1024 * 1024, 8 * 1024 * 1024 + 1}) {
                Path file = write("cover-" + length + ".flac", replacePicture(flac, "image/png", length));
                Snapshot result = candidate(component, file);
                assertEquals(baseline(original, file), result);
                assertEquals(length == 8 * 1024 * 1024, result.cover() != null);
                if (result.cover() != null) assertEquals(length, Base64.getDecoder().decode(result.cover().split(",", 2)[1]).length);
            }
            for (String mime : List.of("image/gif", "application/octet-stream")) {
                Path file = write("mime-" + mime.substring(mime.indexOf('/') + 1) + ".flac", replacePicture(flac, mime, 109));
                assertEquals(baseline(original, file), candidate(component, file));
                assertNull(candidate(component, file).cover());
            }
        }
    }

    @Test void packagedDffTraversalConsumesPaddingAndTerminatesAtMalformedBoundaries() throws Exception {
        List<Path> files = new ArrayList<>(); Map<String, Boolean> expected = new LinkedHashMap<>();
        for (int length : new int[]{0, 1, 2, 3, 4, 14, 15, 16, 254, 255}) {
            addDff(files, expected, "cmpr-" + length, dff("x".repeat(length), new byte[0], new byte[0], new byte[0]), true);
        }
        for (int length : new int[]{0, 1, 2, 3, 4, 15}) {
            byte[] unknown = chunk("JUNK", new byte[length]);
            addDff(files, expected, "unknown-prop-" + length, dff("not compressed ", unknown, new byte[0], new byte[0]), true);
            addDff(files, expected, "unknown-root-" + length, dff("not compressed ", new byte[0], unknown, new byte[0]), true);
            // DffFileReader does not parse ID3: opaque bytes test safe skipping, not ID3 validity.
            addDff(files, expected, "opaque-id3-" + length, dff("not compressed ", new byte[0], new byte[0], chunk("ID3 ", new byte[length])), true);
        }
        byte[] valid = dff("not compressed ", new byte[0], new byte[0], new byte[0]);
        for (int length = 0; length < valid.length; length++) addDff(files, expected, "truncated-" + length, Arrays.copyOf(valid, length), false);
        for (long size : new long[]{-1, Long.MAX_VALUE, 0x80000000L, 1, valid.length * 2L}) {
            byte[] malformed = valid.clone(); int cmpr = indexOf(malformed, "CMPR".getBytes(StandardCharsets.US_ASCII));
            ByteBuffer.wrap(malformed).putLong(cmpr + 4, size);
            addDff(files, expected, "size-" + Long.toUnsignedString(size), malformed, false);
        }
        Path log = temporary.resolve("probe.log");
        String separator = System.getProperty("path.separator");
        String classpath = System.getProperty("musichud.metadata.component") + separator +
                Path.of(MetadataComponentProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", classpath, MetadataComponentProbe.class.getName()));
        files.forEach(path -> command.add(path.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "DFF reader did not finish; see " + log);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception ignored) { return log.toString(); }
            });
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (String line : Files.readAllLines(log)) {
            String[] fields = line.split("\\t");
            if (fields.length == 2 && fields[0].endsWith(".dff")) actual.put(fields[0], Boolean.parseBoolean(fields[1]));
        }
        assertEquals(expected, actual);
    }

    private void addDff(List<Path> files, Map<String, Boolean> expected, String name, byte[] bytes, boolean accepted) throws Exception {
        Path file = write(name + ".dff", bytes); files.add(file); expected.put(file.getFileName().toString(), accepted);
    }
    private Path fixture(String name) throws Exception { return write(name, resource(name)); }
    private Path write(String name, byte[] bytes) throws Exception { return Files.write(temporary.resolve(name), bytes); }
    private static byte[] resource(String name) throws Exception {
        try (var input = AudioMetadataReaderTest.class.getResourceAsStream("/metadata/" + name)) {
            return Objects.requireNonNull(input, name).readAllBytes();
        }
    }
    private static URLClassLoader isolated(Path jar) throws Exception {
        return new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
    private static Snapshot candidate(ClassLoader loader, Path path) throws Exception {
        Class<?> reader = loader.loadClass(AudioMetadataReader.class.getName());
        Object metadata = reader.getMethod("read", Path.class).invoke(null, path);
        return new Snapshot((String) get(metadata, "title"), (String) get(metadata, "artist"), (String) get(metadata, "album"),
                (String) get(metadata, "coverDataUri"), (Integer) get(metadata, "durationMillis"), (Long) get(metadata, "bitrate"));
    }
    private static Object get(Object value, String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }
    private static Snapshot baseline(ClassLoader loader, Path path) throws Exception {
        String title = null, artist = null, album = null, cover = null; Integer duration = null; Long bitrate = null;
        try {
            Object audio = loader.loadClass("org.jaudiotagger.audio.AudioFileIO").getMethod("read", java.io.File.class).invoke(null, path.toFile());
            Object tag = get(audio, "getTag");
            if (tag != null) {
                Class<?> fieldKey = loader.loadClass("org.jaudiotagger.tag.FieldKey");
                Method first = loader.loadClass("org.jaudiotagger.tag.Tag").getMethod("getFirst", fieldKey);
                String text = (String) first.invoke(tag, fieldKey.getField("TITLE").get(null)); if (!text.isBlank()) title = text;
                artist = (String) first.invoke(tag, fieldKey.getField("ARTIST").get(null));
                album = (String) first.invoke(tag, fieldKey.getField("ALBUM").get(null));
                Object art = get(tag, "getFirstArtwork");
                if (art != null) {
                    byte[] bytes = (byte[]) get(art, "getBinaryData");
                    if (bytes != null && bytes.length <= 8 * 1024 * 1024) {
                        String mime = Objects.requireNonNullElse((String) get(art, "getMimeType"), "image/jpeg");
                        if (mime.equals("image/png") || mime.equals("image/jpeg")) cover = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                    }
                }
            }
            Object header = get(audio, "getAudioHeader");
            if (header != null) {
                duration = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(((Number) get(header, "getPreciseTrackLength")).doubleValue() * 1000)));
                if (((Number) get(header, "getBitRateAsNumber")).longValue() > 0) bitrate = ((Number) get(header, "getBitRateAsNumber")).longValue() * 1000;
            }
        } catch (Exception ignored) { }
        return new Snapshot(title, artist, album, cover, duration, bitrate);
    }
    private static byte[] dsf(byte[] mp3) throws Exception {
        int tagLength = 0; for (int i = 6; i < 10; i++) tagLength = (tagLength << 7) | (mp3[i] & 127);
        byte[] tag = Arrays.copyOf(mp3, 10 + tagLength);
        ByteBuffer out = ByteBuffer.allocate(28 + 52 + 12 + 8192 + tag.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("DSD ".getBytes(StandardCharsets.US_ASCII)).putLong(28).putLong(out.capacity()).putLong(out.capacity() - tag.length);
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2)
                .putInt(2822400).putInt(1).putLong(32768).putInt(4096).putInt(0);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putLong(8204).put(new byte[8192]).put(tag); return out.array();
    }
    private static byte[] dff(String description, byte[] extra, byte[] before, byte[] id3) throws Exception {
        byte[] desc = description.getBytes(StandardCharsets.US_ASCII);
        byte[] prop = concat("SND ".getBytes(StandardCharsets.US_ASCII), chunk("FS  ", ByteBuffer.allocate(4).putInt(2822400).array()),
                extra, chunk("CHNL", concat(new byte[]{0, 2}, "SLFTSRGT".getBytes(StandardCharsets.US_ASCII))),
                chunk("CMPR", concat("DSD ".getBytes(StandardCharsets.US_ASCII), new byte[]{(byte) desc.length}, desc)));
        return chunk("FRM8", concat("DSD ".getBytes(StandardCharsets.US_ASCII), chunk("FVER", new byte[]{1, 5, 0, 0}), before,
                chunk("PROP", prop), id3, chunk("DSD ", new byte[64])));
    }
    private static byte[] chunk(String name, byte[] bytes) throws Exception {
        var result = new ByteArrayOutputStream(); var output = new DataOutputStream(result);
        output.write(name.getBytes(StandardCharsets.US_ASCII)); output.writeLong(bytes.length); output.write(bytes);
        if ((bytes.length & 1) != 0) output.writeByte(0); return result.toByteArray();
    }
    private static byte[] concat(byte[]... arrays) throws Exception {
        var output = new ByteArrayOutputStream(); for (byte[] bytes : arrays) output.write(bytes); return output.toByteArray();
    }
    private static byte[] replacePicture(byte[] flac, String mime, int length) throws Exception {
        var output = new ByteArrayOutputStream(); output.write(flac, 0, 4); int cursor = 4;
        while (true) {
            int type = flac[cursor] & 255, size = ((flac[cursor + 1] & 255) << 16) | ((flac[cursor + 2] & 255) << 8) | (flac[cursor + 3] & 255);
            byte[] data = Arrays.copyOfRange(flac, cursor + 4, cursor + 4 + size); cursor += 4 + size;
            if ((type & 127) == 6) {
                ByteBuffer old = ByteBuffer.wrap(data); int pictureType = old.getInt(), mimeLength = old.getInt(); old.position(old.position() + mimeLength);
                int descLength = old.getInt(); byte[] description = new byte[descLength]; old.get(description); byte[] dimensions = new byte[16]; old.get(dimensions);
                int oldLength = old.getInt(); byte[] image = new byte[Math.min(oldLength, length)]; old.get(image);
                var replacement = new ByteArrayOutputStream(); var fields = new DataOutputStream(replacement); byte[] mimeBytes = mime.getBytes(StandardCharsets.US_ASCII);
                fields.writeInt(pictureType); fields.writeInt(mimeBytes.length); fields.write(mimeBytes); fields.writeInt(description.length); fields.write(description);
                fields.write(dimensions); fields.writeInt(length); fields.write(image); fields.write(new byte[length - image.length]); data = replacement.toByteArray();
            }
            output.write(type); output.write((data.length >>> 16) & 255); output.write((data.length >>> 8) & 255); output.write(data.length & 255); output.write(data);
            if ((type & 128) != 0) { output.write(flac, cursor, flac.length - cursor); return output.toByteArray(); }
        }
    }
    private static int indexOf(byte[] bytes, byte[] needle) {
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new IllegalArgumentException("Missing test chunk");
    }
}
