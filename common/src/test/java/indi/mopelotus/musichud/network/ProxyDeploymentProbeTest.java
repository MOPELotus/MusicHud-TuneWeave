package indi.mopelotus.musichud.network;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ProxyDeploymentProbeTest {
    @Test void exactVersionAndDirectionAreRequired() {
        assertTrue(ProxyDeploymentProbe.isRequest(ProxyDeploymentProbe.request()));
        assertTrue(ProxyDeploymentProbe.isPresent(ProxyDeploymentProbe.present()));
        assertFalse(ProxyDeploymentProbe.isRequest(ProxyDeploymentProbe.present()));
        assertFalse(ProxyDeploymentProbe.isPresent(ProxyDeploymentProbe.request()));
        assertFalse(ProxyDeploymentProbe.isRequest(null));
        assertFalse(ProxyDeploymentProbe.isPresent(null));
        for (byte[] valid : new byte[][]{ProxyDeploymentProbe.request(), ProxyDeploymentProbe.present()}) {
            for (int length : new int[]{0, 1, 4, 6, 1024}) {
                byte[] malformed = Arrays.copyOf(valid, length);
                assertFalse(ProxyDeploymentProbe.isRequest(malformed));
                assertFalse(ProxyDeploymentProbe.isPresent(malformed));
            }
            for (int index = 0; index < valid.length; index++) {
                byte[] malformed = valid.clone();
                malformed[index] ^= 64;
                assertFalse(ProxyDeploymentProbe.isRequest(malformed));
                assertFalse(ProxyDeploymentProbe.isPresent(malformed));
            }
        }
    }

    @Test void callersCannotMutateFutureProbes() {
        byte[] request = ProxyDeploymentProbe.request(), present = ProxyDeploymentProbe.present();
        Arrays.fill(request, (byte) 0);
        Arrays.fill(present, (byte) 0);
        assertTrue(ProxyDeploymentProbe.isRequest(ProxyDeploymentProbe.request()));
        assertTrue(ProxyDeploymentProbe.isPresent(ProxyDeploymentProbe.present()));
    }
}
