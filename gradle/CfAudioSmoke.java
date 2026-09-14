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

/** Exercises audio decoding using only the final CF JAR and the host logging API. */
class CfAudioSmoke {
    public static void main(String[] args) throws Exception {
        for (String name : new String[] {
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
            System.out.println("CF final JAR: local and HTTP audio decoded; YouTube implementation absent");
        } finally {
            server.stop(0);
            Files.deleteIfExists(file);
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
