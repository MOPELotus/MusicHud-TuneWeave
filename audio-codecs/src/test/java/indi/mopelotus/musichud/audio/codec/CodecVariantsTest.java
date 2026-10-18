package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodecVariantsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "float-stereo",
        "float-eight",
        "integer-eight",
        "hybrid-stereo",
        "hybrid-float-six"
      })
  void wavpackVariantsPreserveDepthAndExplicitSpeakerMask(String name) throws Exception {
    byte[] expected = resource(name + ".pcm");
    for (int request : new int[] {1, 113, 4096})
      try (var decoder = JavaCodecs.wavpack(new ByteArrayInputStream(resource(name + ".wv")))) {
        int channels = name.endsWith("eight") ? 8 : name.endsWith("six") ? 6 : 2;
        assertEquals(channels, decoder.format().channels());
        assertNotNull(decoder.format().speakers());
        assertEquals(
            channels == 8 ? 255 : channels == 6 ? 63 : 3, decoder.format().speakers().mask());
        assertEquals(
            name.contains("float")
                ? PcmFormat.Encoding.F32LE
                : name.equals("hybrid-stereo")
                    ? PcmFormat.Encoding.S24LE
                    : PcmFormat.Encoding.S32LE,
            decoder.format().encoding());
        assertArrayEquals(expected, read(decoder, request));
        assertEquals(97, decoder.framePosition());
      }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "aiff-s24be-2ch-97f.aiff",
        "aifc-twos-s16be-2ch-97f.aifc",
        "au-6-f32-2ch-97f.au",
        "aiff-ulaw-1ch-97f.aiff"
      })
  void pcmContainersMatchIndependentReference(String file) throws Exception {
    for (int request : new int[] {1, 113, 4096})
      try (var decoder = JavaCodecs.aiffAu(new ByteArrayInputStream(resource(file)))) {
        assertArrayEquals(resource(file + ".pcm"), read(decoder, request));
        assertEquals(97, decoder.framePosition());
      }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void nativeTwentyBitsUseThreeByteLeftAlignedStorage(int channels) throws Exception {
    var input =
        new DataInputStream(
            new ByteArrayInputStream(resource("apple20-" + channels + "ch.packets")));
    byte[] config = input.readNBytes(input.readInt());
    int count = input.readInt();
    try (var decoder = PacketCodecs.alac(new PacketSource.Format("alac", config))) {
      var out = new ByteArrayOutputStream();
      for (int i = 0; i < count; i++) {
        var packet =
            new PacketSource.Packet(
                new PacketSource.Format("alac", config),
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                false,
                ByteBuffer.wrap(input.readNBytes(input.readInt())));
        var decoded = decoder.decode(packet);
        assertEquals(PcmFormat.Encoding.S24LE, decoded.format().encoding());
        assertEquals(channels * 3, decoded.format().frameBytes());
        var pcm = decoded.pcm();
        byte[] bytes = new byte[pcm.remaining()];
        pcm.get(bytes);
        for (int p = 0; p < bytes.length; p += 3) assertEquals(0, bytes[p] & 15);
        out.write(bytes);
      }
      assertArrayEquals(resource("apple20-" + channels + "ch.pcm"), out.toByteArray());
    }
  }

  @Test
  void speakerOrderIsImmutableCompleteAndNeverGuessed() {
    var order = new ArrayList<>(List.of(4, 1, 2));
    var layout = new PcmFormat.SpeakerLayout(7, order);
    order.set(0, 8);
    assertEquals(List.of(4, 1, 2), layout.orderedSpeakerBits());
    assertThrows(UnsupportedOperationException.class, () -> layout.orderedSpeakerBits().set(0, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new PcmFormat.SpeakerLayout(3, List.of(1, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> new PcmFormat.SpeakerLayout(7, List.of(1, 2)));
    assertThrows(
        IllegalArgumentException.class, () -> PcmFormat.SpeakerLayout.fromWaveMask(1L << 32));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PcmFormat(
                48000, 2, PcmFormat.Encoding.S16LE, PcmFormat.Layout.UNSPECIFIED, layout));
    assertNull(
        new PcmFormat(48000, 3, PcmFormat.Encoding.S16LE, PcmFormat.Layout.UNSPECIFIED).speakers());
    assertEquals(
        List.of(1, 2, 4, 8, 16, 32, 64, 128),
        PcmFormat.SpeakerLayout.fromWaveMask(255).orderedSpeakerBits());
  }

  private static byte[] read(PcmDecoder decoder, int request) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] b = new byte[request * decoder.format().frameBytes()];
    int n;
    while ((n = decoder.readFrames(b, 0, request)) != -1)
      out.write(b, 0, n * decoder.format().frameBytes());
    return out.toByteArray();
  }

  private static byte[] resource(String file) throws IOException {
    try (var in = CodecVariantsTest.class.getResourceAsStream("/codecs/variants/" + file)) {
      if (in == null) throw new FileNotFoundException(file);
      return in.readAllBytes();
    }
  }
}
