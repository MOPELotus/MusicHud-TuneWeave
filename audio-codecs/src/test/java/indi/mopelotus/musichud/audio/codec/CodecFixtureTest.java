package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodecFixtureTest {
  @ParameterizedTest
  @ValueSource(strings = {"mp3", "vorbis", "opus", "alac", "aac", "aac-no-pns", "integer"})
  void preservesFramesPrecisionAndChunkPositions(String mode) throws Exception {
    byte[] expected = resource(mode + (mode.equals("integer") ? ".s32" : ".s16"));
    byte[] first = null;
    for (int request : new int[] {1, 113, 4096}) {
      try (var decoder = open(mode)) {
        assertEquals(mode.equals("integer") ? 44100 : 48000, decoder.format().sampleRate());
        assertEquals(2, decoder.format().channels());
        var out = new ByteArrayOutputStream();
        byte[] b = new byte[request * decoder.format().frameBytes() + 7];
        long frames = 0;
        int n;
        assertEquals(0, decoder.readFrames(b, 3, 0));
        while ((n = decoder.readFrames(b, 3, request)) != -1) {
          assertTrue(n > 0 && n <= request);
          frames += n;
          assertEquals(frames, decoder.framePosition());
          out.write(b, 3, n * decoder.format().frameBytes());
        }
        assertEquals(-1, decoder.readFrames(b, 3, request));
        byte[] actual = out.toByteArray();
        if (first == null) first = actual;
        else assertArrayEquals(first, actual, "Chunk request must not change output");
        assertEquals(expected.length, actual.length, "Raw reference length");
        if (mode.equals("integer") || mode.equals("alac") || mode.equals("opus"))
          assertArrayEquals(expected, actual);
        else compareReference(expected, actual, mode);
        decoder.close();
        decoder.cancel();
        assertThrows(IOException.class, () -> decoder.readFrames(b, 0, 1));
      }
    }
  }

  @Test
  void packetConfigChangeIsTerminalAndKeepsTiming() throws Exception {
    try (var source = packets("aac");
        var decoder = PacketCodecs.aac(source.track().initialFormat())) {
      var packet = source.next();
      var output = decoder.decode(packet);
      assertNotNull(output);
      assertSame(packet, output.source());
      assertEquals(1024, output.rawFrames());
      var changed = new PacketSource.Format("mp4a", new byte[] {0x12, 0x10});
      var next =
          new PacketSource.Packet(
              changed,
              null,
              null,
              null,
              PacketSource.Time.ZERO,
              PacketSource.Time.ZERO,
              false,
              ByteBuffer.wrap(new byte[] {0}));
      assertThrows(IOException.class, () -> decoder.decode(next));
      assertThrows(IOException.class, () -> decoder.decode(packet));
    }
    try (var source = packets("alac");
        var decoder = PacketCodecs.alac(source.track().initialFormat())) {
      var original = source.next();
      var timing =
          new PacketSource.Packet(
              original.format(),
              new PacketSource.Time(100, 1000),
              new PacketSource.Time(103, 1000),
              null,
              new PacketSource.Time(1, 48000),
              new PacketSource.Time(7, 48000),
              false,
              original.encoded());
      var decoded = decoder.decode(timing);
      assertSame(timing, decoded.source());
      assertEquals(new PacketSource.Time(103, 1000), decoded.source().presentationTime());
      assertEquals(4096, decoded.rawFrames());
      assertEquals(7, decoded.source().discardEnd().ticks());
      assertNull(decoder.finish());
      assertNull(decoder.finish());
      assertThrows(IOException.class, () -> decoder.decode(original));
    }
  }

  static PcmDecoder open(String mode) throws IOException {
    return switch (mode) {
      case "mp3" -> JavaCodecs.mp3(new ByteArrayInputStream(resource("mp3.mp3")));
      case "vorbis", "opus" -> JavaCodecs.ogg(new ByteArrayInputStream(resource(mode + ".ogg")));
      case "alac", "aac", "aac-no-pns" -> packetStream(mode);
      case "integer" -> JavaCodecs.wavpack(new ByteArrayInputStream(resource("integer.wv")));
      default -> throw new IllegalArgumentException(mode);
    };
  }

  private static PcmDecoder packetStream(String mode) throws IOException {
    PacketSource source = packets(mode);
    PacketDecoder packetDecoder =
        mode.startsWith("aac")
            ? PacketCodecs.aac(source.track().initialFormat())
            : PacketCodecs.alac(source.track().initialFormat());
    final DecodedPacket first = packetDecoder.decode(source.next());
    return new ChunkDecoder(
        first.format(),
        new ChunkDecoder.Core() {
          DecodedPacket pending = first;

          public byte[] next() throws IOException {
            var decoded = pending;
            pending = null;
            while (decoded == null) {
              var packet = source.next();
              if (packet == null) {
                decoded = packetDecoder.finish();
                if (decoded == null) return null;
              } else decoded = packetDecoder.decode(packet);
            }
            var pcm = decoded.pcm();
            byte[] b = new byte[pcm.remaining()];
            pcm.get(b);
            return b;
          }

          public void close() throws IOException {
            try {
              packetDecoder.close();
            } finally {
              source.close();
            }
          }
        });
  }

  static PacketSource packets(String mode) throws IOException {
    var in = new DataInputStream(new ByteArrayInputStream(resource(mode + ".packets")));
    int length = in.readInt();
    byte[] config = in.readNBytes(length);
    int count = in.readInt();
    var packets = new ArrayList<byte[]>();
    for (int i = 0; i < count; i++) packets.add(in.readNBytes(in.readInt()));
    var format = new PacketSource.Format(mode.startsWith("aac") ? "mp4a" : "alac", config);
    var track =
        new PacketSource.Track(
            format, null, List.of(), PacketSource.Time.ZERO, PacketSource.Time.ZERO);
    return new PacketSource() {
      int index;
      boolean closed;

      public Track track() {
        return track;
      }

      public Packet next() throws IOException {
        if (closed) throw new IOException("Closed");
        return index == packets.size()
            ? null
            : new Packet(
                format,
                null,
                null,
                null,
                Time.ZERO,
                Time.ZERO,
                false,
                ByteBuffer.wrap(packets.get(index++)));
      }

      public Time seekMedia(Time target) throws IOException {
        throw new IOException("Not seekable");
      }

      public void close() {
        closed = true;
      }
    };
  }

  static byte[] resource(String name) throws IOException {
    try (var in = CodecFixtureTest.class.getResourceAsStream("/codecs/" + name)) {
      if (in == null) throw new FileNotFoundException(name);
      return in.readAllBytes();
    }
  }

  static void compareReference(byte[] expected, byte[] actual, String mode) {
    var e = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
    var a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
    double error = 0, power = 0;
    int max = 0;
    while (e.hasRemaining()) {
      int ref = e.getShort(), value = a.getShort(), delta = value - ref;
      max = Math.max(max, Math.abs(delta));
      error += (double) delta * delta;
      power += (double) ref * ref;
    }
    if (mode.equals("aac")) compareNoiseEnergy(expected, actual);
    else assertTrue(max <= 1, "Deterministic reference error " + max);
  }

  private static void compareNoiseEnergy(byte[] expected, byte[] actual) {
    var e = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    var a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    int frames = e.remaining() / 2;
    for (int ch = 0; ch < 2; ch++) {
      double refPower = 0, power = 0, cross = 0, refHigh = 0, high = 0;
      for (int f = 0; f < frames; f++) {
        double r = e.get(f * 2 + ch), v = a.get(f * 2 + ch);
        refPower += r * r;
        power += v * v;
        cross += r * v;
        if (f + 2 < frames) {
          double rd = e.get((f + 2) * 2 + ch) - 2 * e.get((f + 1) * 2 + ch) + r;
          double vd = a.get((f + 2) * 2 + ch) - 2 * a.get((f + 1) * 2 + ch) + v;
          refHigh += rd * rd;
          high += vd * vd;
        }
      }
      assertEquals(1, Math.sqrt(power / refPower), 0.001, "PNS channel gain");
      assertTrue(cross / Math.sqrt(power * refPower) > 0.99, "PNS tonal structure and alignment");
      assertEquals(1, high / refHigh, 0.01, "PNS high-frequency noise energy");
    }
  }
}
