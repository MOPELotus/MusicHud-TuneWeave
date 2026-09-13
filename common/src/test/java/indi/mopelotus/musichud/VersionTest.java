package indi.mopelotus.musichud;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {
    @Test
    void enforcesBreakingMajorAndMinimumCompatibleVersion() {
        assertTrue(Version.compatibleWith(Version.LEAST_COMPATIBLE));
        assertTrue(Version.compatibleWith(new Version(2, 1, 0, Version.BuildType.Alpha)));
        assertFalse(Version.compatibleWith(new Version(1, 99, 0, Version.BuildType.Stable)));
        assertFalse(Version.compatibleWith(new Version(3, 0, 0, Version.BuildType.Alpha)));
    }

    @Test
    void roundTripsFixedProtocolVersionShape() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Version.PACKET_CODEC.encode(buffer, Version.CURRENT);
            assertTrue(buffer.readableBytes() > 0);
            assertTrue(Version.CURRENT.equals(Version.PACKET_CODEC.decode(buffer)));
        } finally {
            buffer.release();
        }
    }
}
