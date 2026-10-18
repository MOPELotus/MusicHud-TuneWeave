package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AacRecoveryTest {
  static final Map<String, String> BASELINE =
      Map.of(
          "he-period10-sig0", "5acab0eef80d8fcaf4df35e4ec2d9bf59aaf67b36cf02445bf3691416f9ea852",
          "hev2-period10-sig0", "a5ed5f6113a0cc8a2ce9ae70cc6f05eab703833b41f441da1bf05680d51e9108",
          "he-period72-sig1", "dea85ede61aee203c6958989a15a4546bebd7fcc8eca66095425d37c28a5900b",
          "hev2-period72-sig1", "4bc630bf27b923ec741ed598369dc06795bb98d49c62a5118991f804fc872717");

  record Bundle(PacketSource.Format format, List<byte[]> packets) {
    PacketSource.Packet packet(int index) {
      return new PacketSource.Packet(
          format,
          new PacketSource.Time(index * 2048L, 48000),
          new PacketSource.Time(index * 2048L, 48000),
          new PacketSource.Time(2048, 48000),
          PacketSource.Time.ZERO,
          PacketSource.Time.ZERO,
          false,
          ByteBuffer.wrap(packets.get(index)));
    }
  }

  static Bundle bundle(String name) throws IOException {
    try (var in =
        new DataInputStream(
            new ByteArrayInputStream(
                CodecFixtureTest.resource("aac-recovery/" + name + ".packets")))) {
      byte[] config = in.readNBytes(in.readInt());
      int count = in.readInt();
      var list = new ArrayList<byte[]>();
      for (int n = 0; n < count; n++) list.add(in.readNBytes(in.readInt()));
      assertEquals(-1, in.read());
      return new Bundle(new PacketSource.Format("mp4a", config), List.copyOf(list));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "he-period10-sig0",
        "hev2-period10-sig0",
        "he-period72-sig1",
        "hev2-period72-sig1"
      })
  void successfulObservationPreservesExistingPcm(String name) throws Exception {
    Bundle bundle = bundle(name);
    var digest = MessageDigest.getInstance("SHA-256");
    long frames = 0;
    try (var generic = PacketCodecs.aac(bundle.format())) {
      var decoder = assertInstanceOf(AacPacketDecoder.class, generic);
      assertFalse(decoder.recovery().valid());
      for (int n = 0; n < bundle.packets().size(); n++) {
        var source = bundle.packet(n);
        var decoded = decoder.decode(source);
        var state = decoder.recovery();
        assertTrue(state.valid());
        assertEquals(n + 1, state.accessUnit());
        assertSame(state, decoder.recovery()); // Querying never mutates or decodes anything.
        assertNotNull(decoded);
        assertSame(source, decoded.source());
        frames += decoded.rawFrames();
        byte[] b = new byte[decoded.pcm().remaining()];
        decoded.pcm().get(b);
        digest.update(b);
      }
      assertEquals(bundle.packets().size() * 2048L, frames);
      assertEquals(BASELINE.get(name), HexFormat.of().formatHex(digest.digest()));
      assertNull(decoder.finish());
      decoder.close();
      assertFalse(decoder.recovery().valid());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "he-period10-sig0",
        "hev2-period10-sig0",
        "he-period72-sig1",
        "hev2-period72-sig1"
      })
  void missingHeadersStayUnreadyDespiteCorrectRateAndChannels(String name) throws Exception {
    Bundle bundle = bundle(name);
    int period = name.contains("72") ? 72 : 10;
    boolean ps = name.startsWith("hev2");
    var expected = new AacPacketDecoder.Expectation(48000, 2, true, ps);
    try (var decoder = PacketCodecs.aacWithRecovery(bundle.format(), expected)) {
      assertTrue(decoder.format().isEmpty());
      for (int n = 1; n <= period; n++) {
        var source = bundle.packet(n);
        var pcm = decoder.decode(source);
        var state = decoder.recovery();
        assertTrue(state.valid());
        assertEquals(48000, state.observedRate());
        assertEquals(2, state.observedChannels());
        if (n < period) {
          assertFalse(state.ready());
          assertNull(pcm);
          assertTrue(decoder.format().isEmpty());
        } else {
          assertTrue(state.ready());
          assertNotNull(pcm);
          assertSame(source, pcm.source());
          assertEquals(2048, pcm.rawFrames());
          assertEquals(48000, decoder.format().orElseThrow().sampleRate());
          assertTrue(state.currentAuSbrHeader());
          assertTrue(state.currentAuIndependentSbr());
        }
      }
      var old = decoder.recovery();
      decoder.decode(bundle.packet(period + 1));
      assertEquals(period, old.accessUnit());
      assertTrue(old.ready());
      assertNotSame(old, decoder.recovery());
    }
    if (period == 72) {
      try (var decoder = PacketCodecs.aacWithRecovery(bundle.format(), expected)) {
        for (int n = 73; n < bundle.packets().size(); n++) {
          assertNull(decoder.decode(bundle.packet(n)));
          assertFalse(decoder.recovery().ready());
        }
        assertNull(decoder.finish());
        assertTrue(decoder.format().isEmpty());
      }
    }
  }

  @Test
  void failureInvalidatesObservationAndDecoder() throws Exception {
    Bundle bundle = bundle("he-period10-sig0");
    try (var decoder =
        PacketCodecs.aacWithRecovery(
            bundle.format(), new AacPacketDecoder.Expectation(48000, 2, true, false))) {
      assertNotNull(decoder.decode(bundle.packet(0)));
      assertTrue(decoder.recovery().ready());
      var empty =
          new PacketSource.Packet(
              bundle.format(),
              null,
              null,
              null,
              PacketSource.Time.ZERO,
              PacketSource.Time.ZERO,
              false,
              ByteBuffer.allocate(0));
      assertThrows(IOException.class, () -> decoder.decode(empty));
      assertFalse(decoder.recovery().valid());
      assertThrows(IOException.class, () -> decoder.decode(bundle.packet(0)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AacPacketDecoder.Expectation(48000, 2, false, true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"lc-mono-period10-sig1", "he-mono-period10-sig1"})
  void duplicatedMonoIsNotEvidenceOfParametricStereo(String name) throws Exception {
    Bundle bundle = bundle(name);
    boolean sbr = name.startsWith("he-");
    try (var decoder =
        PacketCodecs.aacWithRecovery(
            bundle.format(), new AacPacketDecoder.Expectation(48000, 2, sbr, false))) {
      for (int n = 1; n <= 10; n++) {
        var pcm = decoder.decode(bundle.packet(n));
        var state = decoder.recovery();
        assertFalse(state.psPresent());
        assertFalse(state.currentAuPsHeader());
        if (!sbr || n == 10) {
          assertTrue(state.ready());
          assertNotNull(pcm);
          assertEquals(2, pcm.format().channels());
        } else {
          assertFalse(state.ready());
          assertNull(pcm);
        }
      }
    }
  }

  @Test
  void singleRateSbrHasAnExplicitCompatibilityBoundary() throws Exception {
    var bundle = bundle("single-rate-sbr");
    var ordinary = assertThrows(IOException.class, () -> PacketCodecs.aac(bundle.format()));
    assertTrue(ordinary.getMessage().contains("single-rate SBR"));
    var recovering =
        assertThrows(
            IOException.class,
            () ->
                PacketCodecs.aacWithRecovery(
                    bundle.format(), new AacPacketDecoder.Expectation(24000, 2, true, false)));
    assertTrue(recovering.getMessage().contains("single-rate SBR"));
  }

  @Test
  void explicitDisabledSbrExtensionRemainsOrdinaryLc() throws Exception {
    try (var source = CodecFixtureTest.packets("aac-no-pns")) {
      var original = source.track().initialFormat();
      byte[] old = original.codecPrivate();
      assertEquals(2, old.length);
      byte[] config = Arrays.copyOf(old, old.length + 3);
      config[old.length] = 0x56;
      config[old.length + 1] = (byte) 0xe5; // sync_extension/aot5/sbr_present_flag0
      var format = new PacketSource.Format("mp4a", config);
      var packet = source.next();
      var wrapped =
          new PacketSource.Packet(
              format,
              null,
              null,
              null,
              PacketSource.Time.ZERO,
              PacketSource.Time.ZERO,
              false,
              packet.encoded());
      try (var ordinary = PacketCodecs.aac(original);
          var recovery =
              PacketCodecs.aacWithRecovery(
                  format, new AacPacketDecoder.Expectation(48000, 2, false, false))) {
        assertEquals(ordinary.decode(packet).pcm(), recovery.decode(wrapped).pcm());
        assertTrue(recovery.recovery().ready());
        assertFalse(recovery.recovery().sbrPresent());
        assertFalse(recovery.recovery().currentAuSbrHeader());
      }
    }
  }

  @Test
  void wrongKnownOutputCannotBePublishedAsReady() throws Exception {
    var bundle = bundle("he-period10-sig0");
    try (var decoder =
        PacketCodecs.aacWithRecovery(
            bundle.format(), new AacPacketDecoder.Expectation(44100, 2, true, false))) {
      for (int n = 0; n < 12; n++) {
        assertNull(decoder.decode(bundle.packet(n)));
        assertTrue(decoder.recovery().valid());
        assertFalse(decoder.recovery().ready());
        assertTrue(decoder.format().isEmpty());
      }
    }
  }

  @Test
  void explicitPsSyncExtensionDoesNotNeedAnExternalFeatureGuess() throws Exception {
    var bundle = bundle("hev2-period72-sig1");
    var config =
        net.sourceforge.jaad.aac.Decoder.create(bundle.format().codecPrivate()).getConfig();
    assertEquals(net.sourceforge.jaad.aac.Profile.AAC_SBR, config.getExtObjectType());
    assertTrue(config.hasParametricStereoSignaling());
    assertTrue(config.hasOutputFrequencyExtension());
    try (var decoder =
        PacketCodecs.aacWithRecovery(
            bundle.format(), new AacPacketDecoder.Expectation(48000, 2, true, false))) {
      assertNull(decoder.decode(bundle.packet(0)));
      assertFalse(decoder.recovery().fullStereoReady());
      assertNotNull(decoder.decode(bundle.packet(1)));
      assertTrue(decoder.recovery().ready());
    }
  }
}
