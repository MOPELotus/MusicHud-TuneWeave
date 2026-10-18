package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OggSingleLinkTest {
  @ParameterizedTest
  @ValueSource(strings = {"same", "mixed", "reused-serial", "multiple-eos", "long-chain"})
  void physicalChainsAndAmbiguousSerialReuseDeclineSingleLinkCapability(String mode)
      throws Exception {
    byte[] input = unsupported(mode);
    var channel = new OggSeekTest.MemoryChannel(input);
    assertThrows(
        OggSeekDecoder.UnsupportedSingleLinkException.class,
        () -> new OggSeekDecoder(() -> channel, input.length));
    assertEquals(1, channel.closes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"header", "tail"})
  void prematurePhysicalEofIsTransportFailureNotCapabilityDecline(String where) throws Exception {
    byte[] input = CodecFixtureTest.resource("vorbis.ogg");
    OggSeekTest.MemoryChannel channel =
        new OggSeekTest.MemoryChannel(input) {
          @Override
          public int read(ByteBuffer target) throws IOException {
            if (where.equals("header") && cursor >= 10) return -1;
            if (where.equals("header")) {
              int priorLimit = target.limit();
              target.limit(target.position() + Math.min(target.remaining(), 10 - cursor));
              try {
                return super.read(target);
              } finally {
                target.limit(priorLimit);
              }
            }
            return super.read(target);
          }
        };
    var failure =
        assertThrows(
            IOException.class,
            () -> new OggSeekDecoder(() -> channel, input.length + (where.equals("tail") ? 1 : 0)));
    assertInstanceOf(EOFException.class, failure);
    assertFalse(failure instanceof OggSeekDecoder.UnsupportedSingleLinkException);
    assertEquals(1, channel.closes);
  }

  @Test
  void channelIdentityOrNetworkErrorIsNotReclassifiedAsCapability() throws Exception {
    byte[] input = CodecFixtureTest.resource("vorbis.ogg");
    IOException marker = new IOException("Snapshot identity changed");
    var channel =
        new OggSeekTest.MemoryChannel(input) {
          @Override
          public int read(ByteBuffer target) throws IOException {
            if (cursor == 0 && target.remaining() > 27) throw marker;
            return super.read(target);
          }
        };
    var failure =
        assertThrows(IOException.class, () -> new OggSeekDecoder(() -> channel, input.length));
    assertSame(marker, failure);
    assertEquals(1, channel.closes);
  }

  @Test
  void actualShadedArtifactRetainsTypedDeclineWithoutConvertingEarlyEof() throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var loader =
        new URLClassLoader(
            new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      String prefix = "indi.mopelotus.musichud.audio.codec.";
      var type = loader.loadClass(prefix + "OggSeekDecoder");
      var sourceType = loader.loadClass(prefix + "OggSeekDecoder$Source");
      var declineType = loader.loadClass(prefix + "OggSeekDecoder$UnsupportedSingleLinkException");
      assertTrue(IOException.class.isAssignableFrom(declineType));
      byte[] chain = unsupported("mixed");
      var channel = new OggSeekTest.MemoryChannel(chain);
      Object source =
          java.lang.reflect.Proxy.newProxyInstance(
              loader, new Class<?>[] {sourceType}, (proxy, method, args) -> channel);
      var declined =
          assertThrows(
              InvocationTargetException.class,
              () ->
                  type.getConstructor(sourceType, long.class)
                      .newInstance(source, (long) chain.length));
      assertTrue(declineType.isInstance(declined.getCause()));
      assertEquals(1, channel.closes);
      byte[] single = CodecFixtureTest.resource("vorbis.ogg");
      var truncated = new OggSeekTest.MemoryChannel(single);
      Object invalid =
          java.lang.reflect.Proxy.newProxyInstance(
              loader, new Class<?>[] {sourceType}, (proxy, method, args) -> truncated);
      var failed =
          assertThrows(
              InvocationTargetException.class,
              () ->
                  type.getConstructor(sourceType, long.class)
                      .newInstance(invalid, single.length + 1L));
      assertInstanceOf(EOFException.class, failed.getCause());
      assertFalse(declineType.isInstance(failed.getCause()));
      assertEquals(1, truncated.closes);
    }
  }

  static byte[] unsupported(String mode) throws IOException {
    if (mode.equals("reused-serial")) {
      byte[] input = CodecFixtureTest.resource("vorbis.ogg");
      return OggLinksTest.concat(input, input);
    }
    if (mode.equals("multiple-eos")) {
      byte[] input = CodecFixtureTest.resource("vorbis.ogg");
      int p = 0, end = 0;
      while (p < input.length) {
        end = p;
        p += OggLinksTest.pageSize(input, p);
      }
      byte[] extra = Arrays.copyOfRange(input, end, input.length);
      ByteBuffer.wrap(extra)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(18, ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).getInt(18) + 1);
      OggLinksTest.crc(extra, 0);
      return OggLinksTest.concat(input, extra);
    }
    if (mode.equals("long-chain")) {
      var pair = OggLinksTest.pair("mixed");
      var out = new ByteArrayOutputStream();
      out.write(pair.first());
      for (int i = 0; i < 200; i++) out.write(pair.second());
      return out.toByteArray();
    }
    return OggLinksTest.pair(mode).physical();
  }
}
