package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolInfoTest {
    @Test void collectionCodecUsesOneSnapshotAndCapsDeclaredElementCount() {
        var codec = Codecs.ofList(() -> Codecs.STRING_UTF8);
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, java.util.Arrays.asList("one", null, "two"));
            assertEquals(java.util.List.of("one", "two"), codec.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear().writeInt(100_001).writeZero(100_001);
            assertThrows(DecoderException.class, () -> Codecs.ofList(() -> Codecs.BYTE).decode(buffer));
            assertThrows(IllegalArgumentException.class, () -> codec.encode(buffer, java.util.Collections.nCopies(100_001, "")));
        } finally { buffer.release(); }
    }

    @Test
    void requiresTuneWeaveIdentityAndPlaybackSessionCapability() {
        assertTrue(ProtocolInfo.isCompatible(
                ProtocolInfo.PROJECT_ID, Version.CURRENT, ProtocolInfo.CAPABILITIES));
        assertFalse(ProtocolInfo.isCompatible(
                "music_hud", Version.CURRENT, ProtocolInfo.CAPABILITIES));
        assertFalse(ProtocolInfo.isCompatible(
                ProtocolInfo.PROJECT_ID, Version.CURRENT, Set.of()));
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROJECT_ID, Version.CURRENT,
                Set.of(ProtocolCapability.PUBLIC_PLAYBACK_SESSION)));
    }

    @Test
    void rejectsPreBreakingProtocolVersions() {
        assertFalse(ProtocolInfo.isCompatible(
                ProtocolInfo.PROJECT_ID,
                new Version(1, 3, 0, Version.BuildType.Alpha),
                ProtocolInfo.CAPABILITIES));
        assertFalse(ProtocolInfo.isCompatible(
                ProtocolInfo.PROJECT_ID,
                new Version(3, 0, 0, Version.BuildType.Alpha),
                ProtocolInfo.CAPABILITIES));
    }

    @Test
    void rejectsMalformedHandshakeFieldsBeforeMembership() {
        assertFalse(ProtocolInfo.isCompatible(null, Version.CURRENT, ProtocolInfo.CAPABILITIES));
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROJECT_ID, null, ProtocolInfo.CAPABILITIES));
        assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROJECT_ID, Version.CURRENT, null));
    }

    @Test
    void handshakeResponseContainsOnlyProtocolIdentityAndCapabilities() {
        ConnectResponse source = ConnectResponse.current(true);
        ByteBuf buffer = Unpooled.buffer();
        try {
            ConnectResponse.CODEC.encode(buffer, source);
            ConnectResponse decoded = ConnectResponse.CODEC.decode(buffer);

            assertTrue(decoded.accepted());
            assertEquals(ProtocolInfo.PROJECT_ID, decoded.projectId());
            assertEquals(Version.CURRENT, decoded.serverVersion());
            assertEquals(ProtocolInfo.CAPABILITIES, decoded.capabilities());
        } finally {
            buffer.release();
        }
    }

    @Test
    void collectionCodecRejectsNegativeOrImpossibleLength() {
        var codec = Codecs.ofList(() -> Codecs.BYTE);
        ByteBuf buffer = Unpooled.buffer().writeInt(-1);
        try {
            assertThrows(DecoderException.class, () -> codec.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
