package indi.mopelotus.musichud.audio.container;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class ShadedPacketPcmProbe {
  public static void main(String[] a) throws Exception {
    try (var loader =
            new URLClassLoader(
                new URL[] {
                  Path.of(a[0]).toUri().toURL(),
                  Path.of(a[1]).toUri().toURL(),
                  Path.of(a[2]).toUri().toURL()
                },
                ClassLoader.getPlatformClassLoader());
        var output = Files.newOutputStream(Path.of(a[4]))) {
      loader.setDefaultAssertionStatus(Boolean.parseBoolean(a[5]));
      String p = "indi.mopelotus.musichud.audio.";
      var sourceType = loader.loadClass(p + "container.Mp4PacketSource");
      var factory = loader.loadClass(p + "codec.PacketCodecs");
      var formatType = loader.loadClass(p + "codec.PacketSource$Format");
      var packetType = loader.loadClass(p + "codec.PacketSource$Packet");
      var decoderType = loader.loadClass(p + "codec.PacketDecoder");
      Object source = sourceType.getConstructor(Path.class).newInstance(Path.of(a[3]));
      Object decoder = null, previous = null;
      int packets = 0;
      Object track = sourceType.getMethod("track").invoke(source);
      Object initial = track.getClass().getMethod("initialFormat").invoke(track);
      try {
        if ("mp3".equals(formatType.getMethod("codec").invoke(initial))) {
          var raw = new ByteArrayOutputStream();
          Object item;
          while ((item = sourceType.getMethod("next").invoke(source)) != null) {
            Object format = packetType.getMethod("format").invoke(item);
            if (!initial.equals(format)) throw new AssertionError("MP3 sample format changed");
            byte[] config = (byte[]) formatType.getMethod("codecPrivate").invoke(format);
            if (config.length != 0) throw new AssertionError("Unexpected MP3 codecPrivate");
            ByteBuffer encoded = (ByteBuffer) packetType.getMethod("encoded").invoke(item);
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            raw.write(bytes);
            packets++;
          }
          var codecs = loader.loadClass(p + "codec.JavaCodecs");
          var streamType = loader.loadClass(p + "codec.PcmDecoder");
          decoder =
              codecs
                  .getMethod("mp3RawMedia", InputStream.class)
                  .invoke(null, new ByteArrayInputStream(raw.toByteArray()));
          Object format = streamType.getMethod("format").invoke(decoder);
          int frameBytes = (int) format.getClass().getMethod("frameBytes").invoke(format);
          byte[] buffer = new byte[4096 * frameBytes];
          int frames;
          while ((frames =
                  (int)
                      streamType
                          .getMethod("readFrames", byte[].class, int.class, int.class)
                          .invoke(decoder, buffer, 0, 4096))
              > 0) output.write(buffer, 0, frames * frameBytes);
          System.out.println("mp3 packets=" + packets);
          return;
        }
        Object packet;
        while ((packet = sourceType.getMethod("next").invoke(source)) != null) {
          Object format = packetType.getMethod("format").invoke(packet);
          if (packets == 0 && !initial.equals(format))
            throw new AssertionError("Initial format differs from first packet");
          String codec = (String) formatType.getMethod("codec").invoke(format);
          byte[] config = (byte[]) formatType.getMethod("codecPrivate").invoke(format);
          if (decoder == null
              || !format.equals(previous)
              || (boolean) packetType.getMethod("discontinuity").invoke(packet)) {
            if (decoder != null) ((AutoCloseable) decoder).close();
            if (codec.equals("alac") && config.length != 36)
              throw new AssertionError("ALAC config " + config.length);
            decoder =
                factory
                    .getMethod(codec.equals("alac") ? "alac" : "aac", formatType)
                    .invoke(null, format);
            previous = format;
            System.out.println(codec + " config=" + HexFormat.of().formatHex(config));
          }
          Object decoded = decoderType.getMethod("decode", packetType).invoke(decoder, packet);
          if (decoded != null) {
            ByteBuffer bytes = (ByteBuffer) decoded.getClass().getMethod("pcm").invoke(decoded);
            byte[] b = new byte[bytes.remaining()];
            bytes.get(b);
            output.write(b);
          }
          packets++;
        }
        System.out.println("packets=" + packets);
      } finally {
        if (decoder != null) ((AutoCloseable) decoder).close();
        ((AutoCloseable) source).close();
      }
    }
  }
}
