package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.platform.mod.network.DeploymentProbePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentProbePayloadTest {
    @Test void fixedMarkersMatchPluginFormatAndOnlyRequestRequestsReply() {
        assertMarker(ProxyDeploymentProbe.request(), true, false);
        assertMarker(ProxyDeploymentProbe.present(), false, true);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            DeploymentProbePayload.CODEC.encode(buffer, DeploymentProbePayload.presence());
            byte[] actual = new byte[buffer.readableBytes()]; buffer.readBytes(actual);
            assertArrayEquals(ProxyDeploymentProbe.present(), actual);
            assertEquals(ProxyDeploymentProbe.CHANNEL, DeploymentProbePayload.TYPE.id().toString());
            assertThrows(IllegalArgumentException.class, () -> DeploymentProbePayload.CODEC.encode(buffer, new DeploymentProbePayload(false, false)));
        } finally { buffer.release(); }
    }
    @Test void malformedLengthsVersionsDirectionsAndTrailingBytesAreIgnored() {
        for (byte[] marker : new byte[][]{ProxyDeploymentProbe.request(), ProxyDeploymentProbe.present()}) {
            for (int length : new int[]{0, 1, 4, 6, 1024, 32768}) assertMarker(Arrays.copyOf(marker, length), false, false);
            for (int i = 0; i < marker.length; i++) {
                byte[] modified = marker.clone(); modified[i] ^= 64;
                assertMarker(modified, false, false);
            }
        }
    }
    private void assertMarker(byte[] bytes, boolean request, boolean present) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
        try {
            var decoded = DeploymentProbePayload.CODEC.decode(buffer);
            assertEquals(request, decoded.request()); assertEquals(present, decoded.present());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
}
