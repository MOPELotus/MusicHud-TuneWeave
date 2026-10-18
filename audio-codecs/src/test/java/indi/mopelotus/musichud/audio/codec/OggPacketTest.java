package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OggPacketTest {
  static PacketDecoder open(String codec, PacketSource.Format format) throws IOException {
    return codec.equals("opus") ? PacketCodecs.opus(format) : PacketCodecs.vorbis(format);
  }

  static byte[] decode(String codec) throws Exception {
    try (var source = CodecFixtureTest.packets(codec + "-packet");
        var decoder = open(codec, source.track().initialFormat())) {
      var out = new ByteArrayOutputStream();
      PacketSource.Packet p;
      int index = 0;
      while ((p = source.next()) != null) {
        var timed =
            new PacketSource.Packet(
                p.format(),
                new PacketSource.Time(index++, 1000),
                new PacketSource.Time(index * 3, 1000),
                new PacketSource.Time(1, 100),
                new PacketSource.Time(13, 48000),
                new PacketSource.Time(17, 48000),
                false,
                p.encoded());
        var decoded = decoder.decode(timed);
        if (decoded != null) {
          assertSame(timed, decoded.source());
          assertEquals(48000, decoded.format().sampleRate());
          assertEquals(2, decoded.format().channels());
          assertEquals(PcmFormat.Layout.STEREO, decoded.format().layout());
          assertTrue(decoded.rawFrames() > 0);
          var b = decoded.pcm();
          byte[] bytes = new byte[b.remaining()];
          b.get(bytes);
          out.write(bytes);
          assertEquals(bytes.length, decoded.pcm().remaining());
        }
      }
      assertNull(decoder.finish());
      assertNull(decoder.finish());
      return out.toByteArray();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"vorbis", "opus"})
  void rawPacketTimingRemainsOwnedByContainer(String codec) throws Exception {
    byte[] actual = decode(codec), expected = CodecFixtureTest.resource(codec + ".s16");
    int skip = 0;
    if (codec.equals("opus")) {
      try (var source = CodecFixtureTest.packets(codec + "-packet")) {
        byte[] h = source.track().initialFormat().codecPrivate();
        skip = ((h[10] & 255) | ((h[11] & 255) << 8)) * 4;
      }
      assertTrue(skip > 0);
    }
    assertTrue(
        actual.length >= skip + expected.length, "Raw packets must retain container padding");
    byte[] presentation = Arrays.copyOfRange(actual, skip, skip + expected.length);
    if (codec.equals("opus")) assertArrayEquals(expected, presentation);
    else CodecFixtureTest.compareReference(expected, presentation, "vorbis");
    assertArrayEquals(actual, decode(codec), "Independent instances must preserve decoder state");
  }

  @ParameterizedTest
  @ValueSource(strings = {"vorbis", "opus"})
  void configurationAndDiscontinuityRequireReset(String codec) throws Exception {
    try (var source = CodecFixtureTest.packets(codec + "-packet")) {
      var format = source.track().initialFormat();
      var packet = source.next();
      try (var decoder = open(codec, format)) {
        decoder.decode(packet);
        var reset =
            new PacketSource.Packet(
                format,
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                true,
                packet.encoded());
        assertThrows(IOException.class, () -> decoder.decode(reset));
        assertThrows(IOException.class, () -> decoder.decode(packet));
      }
      try (var decoder = open(codec, format)) {
        var other = new PacketSource.Format(codec, new byte[] {0});
        var changed =
            new PacketSource.Packet(
                other,
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                false,
                packet.encoded());
        assertThrows(IOException.class, () -> decoder.decode(changed));
        assertThrows(IOException.class, decoder::finish);
      }
      try (var decoder = open(codec, format)) {
        var empty =
            new PacketSource.Packet(
                format,
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                false,
                ByteBuffer.allocate(0));
        assertThrows(IOException.class, () -> decoder.decode(empty));
        assertThrows(IOException.class, () -> decoder.decode(packet));
      }
    }
    assertThrows(
        IOException.class, () -> open(codec, new PacketSource.Format(codec, new byte[] {2})));
    assertThrows(
        IOException.class, () -> open(codec, new PacketSource.Format("other", new byte[] {2})));
  }

  @Test
  void multichannelOpusRetainsVerifiedVorbisOrderAndRawFrames() throws Exception {
    try (var source = CodecFixtureTest.packets("opus-six");
        var decoder = PacketCodecs.opus(source.track().initialFormat())) {
      assertEquals(PcmFormat.Layout.VORBIS, decoder.format().orElseThrow().layout());
      assertEquals(6, decoder.format().orElseThrow().channels());
      var out = new ByteArrayOutputStream();
      PacketSource.Packet p;
      while ((p = source.next()) != null) {
        var decoded = decoder.decode(p);
        assertSame(p, decoded.source());
        byte[] b = new byte[decoded.pcm().remaining()];
        decoded.pcm().get(b);
        out.write(b);
      }
      assertArrayEquals(CodecFixtureTest.resource("opus-six.s16"), out.toByteArray());
      assertNull(decoder.finish());
    }
  }

  @Test
  void opusHeaderPreSkipIsNotHiddenTimingAndUnknownMappingIsRejected() throws Exception {
    try (var source = CodecFixtureTest.packets("opus-packet")) {
      var f = source.track().initialFormat();
      var p = source.next();
      byte[] h = f.codecPrivate();
      h[10] = 0;
      h[11] = 0;
      var noDelay = new PacketSource.Format("opus", h);
      try (var first = PacketCodecs.opus(f);
          var second = PacketCodecs.opus(noDelay)) {
        var changed =
            new PacketSource.Packet(
                noDelay,
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                false,
                p.encoded());
        assertEquals(first.decode(p).pcm(), second.decode(changed).pcm());
      }
      h[18] = 2;
      assertThrows(IOException.class, () -> PacketCodecs.opus(new PacketSource.Format("opus", h)));
    }
    try (var source = CodecFixtureTest.packets("opus-six")) {
      byte[] h = source.track().initialFormat().codecPrivate();
      h[21] = 1;
      assertThrows(IOException.class, () -> PacketCodecs.opus(new PacketSource.Format("opus", h)));
    }
  }
}
