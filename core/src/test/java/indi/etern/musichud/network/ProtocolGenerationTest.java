package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolGenerationTest {
    private static final Version LEGACY = new Version(2, 0, 0, Version.BuildType.Alpha);

    @Test void legacyHandshakeLayoutRemainsReadableAndBothMajorsRejectEachOther() {
        assertEquals(new Version(3, 0, 0, Version.BuildType.Alpha), Version.CURRENT);
        assertEquals(Version.CURRENT, Version.LEAST_COMPATIBLE);
        assertEquals(8, ProtocolCapability.values().length, "Handshake capability ordinals are unchanged");
        var buffer = Unpooled.buffer();
        try {
            // Actual generation-3 request is readable by the unchanged generation-2 field layout.
            ConnectRequest.CODEC.encode(buffer, ConnectRequest.current());
            assertEquals(ProtocolInfo.PROJECT_ID, Codecs.STRING_UTF8.decode(buffer));
            Version presentedToLegacy = Version.PACKET_CODEC.decode(buffer);
            assertEquals(ProtocolInfo.CAPABILITIES, capabilities().decode(buffer));
            assertFalse(buffer.isReadable());
            assertFalse(legacyCompatible(presentedToLegacy));

            // Actual generation-2 request layout decodes normally, then fails the new major gate.
            buffer.clear();
            encodeLegacyHandshake(buffer, LEGACY);
            var oldRequest = ConnectRequest.CODEC.decode(buffer);
            assertFalse(buffer.isReadable());
            assertFalse(ProtocolInfo.isCompatible(oldRequest.projectId(), oldRequest.clientVersion(), oldRequest.capabilities()));

            // A refusing response has no date field and remains readable in either direction.
            for (Version responder : new Version[] {LEGACY, Version.CURRENT}) {
                buffer.clear();
                ConnectResponse.CODEC.encode(buffer,
                        new ConnectResponse(false, ProtocolInfo.PROJECT_ID, responder, ProtocolInfo.CAPABILITIES));
                assertFalse(buffer.readBoolean());
                assertEquals(ProtocolInfo.PROJECT_ID, Codecs.STRING_UTF8.decode(buffer));
                assertEquals(responder, Version.PACKET_CODEC.decode(buffer));
                assertEquals(ProtocolInfo.CAPABILITIES, capabilities().decode(buffer));
                assertFalse(buffer.isReadable());
            }
        } finally { buffer.release(); }
    }

    @Test void rejectsLegacyMinorPatchAndBuildVariantsDespiteCompleteCapabilities() {
        for (Version.BuildType build : Version.BuildType.values()) {
            for (Version old : new Version[] {new Version(2, 0, 0, build), new Version(2, 99, 99, build)}) {
                assertFalse(ProtocolInfo.isCompatible(ProtocolInfo.PROJECT_ID, old, ProtocolInfo.CAPABILITIES));
                assertFalse(legacyCompatible(Version.CURRENT));
            }
        }
    }

    @Test void truncatedUnchangedHandshakesStillFailBeforeDispatch() {
        var buffer = Unpooled.buffer();
        try {
            ConnectRequest.CODEC.encode(buffer, ConnectRequest.current());
            for (int size = 0; size < buffer.writerIndex(); size++) {
                var partial = buffer.slice(0, size);
                assertThrows(RuntimeException.class, () -> ConnectRequest.CODEC.decode(partial));
            }
        } finally { buffer.release(); }
    }

    private static ByteBufCodec<Set<ProtocolCapability>> capabilities() {
        return Codecs.ofSet(() -> Codecs.ofEnum(ProtocolCapability.class));
    }

    private static void encodeLegacyHandshake(ByteBuf buffer, Version version) {
        Codecs.STRING_UTF8.encode(buffer, ProtocolInfo.PROJECT_ID);
        Version.PACKET_CODEC.encode(buffer, version);
        capabilities().encode(buffer, ProtocolInfo.CAPABILITIES);
    }

    /** The deployed generation-2 Version.compatibleWith rule, before the major bump. */
    private static boolean legacyCompatible(Version presented) {
        return presented.major() == 2 && LEGACY.compareTo(presented) <= 0;
    }
}
