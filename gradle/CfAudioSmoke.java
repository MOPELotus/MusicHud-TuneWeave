import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Exercises packaged decoding with only the final JAR and explicitly provided host libraries. */
class CfAudioSmoke {
    public static void main(String[] args) throws Exception {
        boolean cf = args.length == 0 || args[0].equals("cf");
        verifyHostLibraries();
        if (cf) for (String name : new String[] {
                "com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAccessTokenTracker",
                "com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager",
                "com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers"}) {
            try {
                Class.forName(name);
                throw new AssertionError("Unused YouTube implementation remains: " + name);
            } catch (ClassNotFoundException expected) { }
        }
        int samples = 48000;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(wav.capacity() - 8).put("WAVEfmt ".getBytes());
        wav.putInt(16).putShort((short) 1).putShort((short) 2).putInt(48000);
        wav.putInt(192000).putShort((short) 4).putShort((short) 16);
        wav.put("data".getBytes()).putInt(samples * 4);
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(i * 2 * Math.PI * 440 / 48000) * 8000);
            wav.putShort(value).putShort(value);
        }
        var file = Files.createTempFile("musichud-cf-audio-", ".wav");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio.wav", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "audio/wav");
                exchange.sendResponseHeaders(200, wav.capacity());
                exchange.getResponseBody().write(wav.array());
            }
        });
        try {
            Files.write(file, wav.array());
            server.start();
            decode(file.toString());
            decode("http://127.0.0.1:" + server.getAddress().getPort() + "/audio.wav");
            System.out.println("Final " + (cf ? "CF" : "standard") + " JAR: local and HTTP audio decoded with host Commons IO/Codec");
        } finally {
            server.stop(0);
            Files.deleteIfExists(file);
        }
    }

    private static void verifyHostLibraries() throws Exception {
        for (Class<?> type : new Class<?>[] {org.apache.commons.io.IOUtils.class,
                org.apache.commons.codec.binary.Base64.class}) {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            if (!location.getPath().contains("/" + (type.getName().contains(".io.") ? "commons-io-" : "commons-codec-"))) {
                throw new AssertionError("Utility library was not loaded from the host: " + type.getName());
            }
        }
        byte[] text = "TuneWeave 音乐 🎵".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] copied = org.apache.commons.io.IOUtils.toByteArray(new java.io.ByteArrayInputStream(text));
        byte[] decoded = org.apache.commons.codec.binary.Base64.decodeBase64(
                org.apache.commons.codec.binary.Base64.encodeBase64(copied));
        if (!java.util.Arrays.equals(text, decoded)) throw new AssertionError("Host utility round trip failed");
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(text));
        if (!expected.equals(org.apache.commons.codec.digest.DigestUtils.md5Hex(text))) {
            throw new AssertionError("Host digest differs");
        }
    }

    private static void decode(String identifier) throws Exception {
        var manager = new DefaultAudioPlayerManager();
        try {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
            manager.registerSourceManager(new HttpAudioSourceManager());
            manager.registerSourceManager(new LocalAudioSourceManager());
            var item = manager.loadItemSync(identifier);
            if (!(item instanceof AudioTrack track)) throw new AssertionError("Audio track did not load");
            var player = manager.createPlayer();
            try {
                player.playTrack(track);
                AudioFrame frame = player.provide(10, TimeUnit.SECONDS);
                if (frame == null || frame.isTerminator() || frame.getDataLength() == 0) {
                    throw new AssertionError("Audio track did not decode to PCM");
                }
                boolean audible = false;
                for (byte value : frame.getData()) audible |= value != 0;
                if (!audible) throw new AssertionError("Decoded frame contains only silence");
            } finally {
                player.destroy();
            }
        } finally {
            manager.shutdown();
        }
    }
}
