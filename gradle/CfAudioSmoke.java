import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Decode actual encoded samples using only the distributed JAR and host logging APIs. */
class CfAudioSmoke {
  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Expected distribution and fixture directory");
    Path fixtures = Path.of(args[1]);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String[] samples = {
      "tone.mp3", "tone.flac", "tone.wav", "tone.m4a", "tone.opus", "tone.alac.m4a", "chain-rate-change.ogg"
    };
    for (int i = 0; i < samples.length; i++) {
      byte[] bytes = Files.readAllBytes(fixtures.resolve(samples[i]));
      // Deliberately no extension and no useful Content-Type: sniff the bytes.
      server.createContext(
          "/sample/" + i,
          exchange -> {
            try (exchange) {
              if (!"required".equals(exchange.getRequestHeaders().getFirst("X-Audio-Smoke"))) {
                exchange.sendResponseHeaders(403, -1);
                return;
              }
              exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
    }
    try {
      server.start();
      for (int i = 0; i < samples.length; i++) {
        byte[] local = decode(fixtures.resolve(samples[i]).toString(), Map.of());
        byte[] remote =
            decode(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/sample/" + i,
                Map.of("X-Audio-Smoke", "required"));
        if (!Arrays.equals(local, remote))
          throw new AssertionError("Local/HTTP PCM differ: " + samples[i]);
        if (samples[i].equals("chain-rate-change.ogg")) {
          int firstLinkBytes = decode(fixtures.resolve("tone.opus").toString(), Map.of()).length;
          if (local.length < firstLinkBytes + 48000 / 20 * 4)
            throw new AssertionError("Chained sample lost its resampled logical stream");
          boolean tailSignal = false;
          for (int index = firstLinkBytes; index < local.length; index++) tailSignal |= local[index] != 0;
          if (!tailSignal) throw new AssertionError("Resampled logical stream is silent");
        }
        System.out.println(
            "Packaged "
                + args[0]
                + " decoded local and HTTP "
                + samples[i]
                + ": "
                + local.length
                + " bytes");
      }
    } finally {
      server.stop(0);
      try {
        Class.forName("indi.mopelotus.musichud.client.audio.decoder.PcmAudioInput")
            .getMethod("closeTransport")
            .invoke(null);
      } catch (InvocationTargetException error) {
        throw new AssertionError("Transport shutdown failed", error.getCause());
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static byte[] decode(String identifier, Map<String, String> headers) throws Exception {
    Class<?> factory =
        Class.forName("indi.mopelotus.musichud.client.audio.decoder.AudioDecoderFactory");
    Class<?> formatType = Class.forName("indi.mopelotus.musichud.beans.music.FormatType");
    Class<?> decoderType =
        Class.forName("indi.mopelotus.musichud.client.audio.decoder.AudioDecoder");
    Object auto = Enum.valueOf((Class) formatType, "AUTO");
    Object decoder;
    try {
      decoder =
          factory
              .getMethod("open", String.class, formatType, Map.class)
              .invoke(null, identifier, auto, headers);
    } catch (InvocationTargetException error) {
      throw new AssertionError("Cannot open packaged sample", error.getCause());
    }
    try {
      if (decoder.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("lava")) {
        throw new AssertionError("Old backend remains active");
      }
      int frameSize = (Integer) decoderType.getMethod("getFrameSize").invoke(decoder);
      int rate = (Integer) decoderType.getMethod("getSampleRate").invoke(decoder);
      if (frameSize <= 0 || rate < 8000 || rate > 384000)
        throw new AssertionError("Invalid PCM metadata");
      var pcm = new ByteArrayOutputStream();
      for (int reads = 0; reads < 10000; reads++) {
        byte[] chunk =
            (byte[]) decoderType.getMethod("readChunk", long.class).invoke(decoder, 4096L);
        if (chunk == null || chunk.length == 0) break;
        if (chunk.length % frameSize != 0) throw new AssertionError("Partial PCM frame");
        pcm.write(chunk);
        if (pcm.size() > 2_000_000 || reads == 9999)
          throw new AssertionError("Unbounded sample output");
      }
      byte[] bytes = pcm.toByteArray();
      if (bytes.length / frameSize < rate / 20) throw new AssertionError("Truncated PCM");
      boolean signal = false;
      for (byte value : bytes) signal |= value != 0;
      if (!signal) throw new AssertionError("Decoded output is silent");
      return bytes;
    } finally {
      decoderType.getMethod("close").invoke(decoder);
    }
  }
}
