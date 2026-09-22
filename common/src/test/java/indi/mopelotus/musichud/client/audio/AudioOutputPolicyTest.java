package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioOutputPolicyTest {
    @Test void malformedConfigDefaultsToExistingMultichannelBehavior() {
        assertEquals(AudioOutputMode.MULTICHANNEL, AudioOutputMode.parse(null));
        for (String value : new String[]{"", "invalid", "STEREO,ALL", "true"})
            assertEquals(AudioOutputMode.MULTICHANNEL, AudioOutputMode.parse(value));
        assertEquals(AudioOutputMode.STEREO, AudioOutputMode.parse(" stereo "));
    }

    @Test void unrelatedErrorsAndStaleDeviceCannotChangePolicyAndFreshDeviceRetriesNativeFormat() {
        var policy = new AudioOutputPolicy(); policy.device(17, 2);
        int surround = OpenAlFormatSelector.select(6, true);
        for (int i = 0; i < 4; i++) {
            assertFalse(policy.rejected(new OpenAlFailure(OpenAlFailure.Kind.DEVICE_LOST, "alBufferData", 0xA002, 17, 2), surround));
            assertFalse(policy.rejected(new OpenAlFailure(OpenAlFailure.Kind.OPERATION, "gain", 0xA002, 17, 2), surround));
            assertFalse(policy.rejected(new OpenAlFailure(OpenAlFailure.Kind.OPERATION, "alBufferData", 0xA002, 17, 1), surround));
        }
        assertEquals(surround, policy.format(surround, AudioOutputMode.MULTICHANNEL, true, true));
        var rejected = new OpenAlFailure(OpenAlFailure.Kind.OPERATION, "alBufferData", 0xA002, 17, 2);
        assertFalse(policy.rejected(rejected, surround)); assertFalse(policy.rejected(rejected, surround));
        assertTrue(policy.rejected(rejected, surround));
        assertEquals(2, OpenAlFormatSelector.channels(policy.format(surround, AudioOutputMode.MULTICHANNEL, true, true)));
        policy.device(17, 3);
        assertEquals(surround, policy.format(surround, AudioOutputMode.MULTICHANNEL, true, true));
    }
    @Test void discreteOnlyNeverSilentlyFallsBackToStereo() {
        var policy = new AudioOutputPolicy(); policy.device(17, 2);
        int surround = OpenAlFormatSelector.select(6, false);
        assertEquals(surround, policy.format(surround, AudioOutputMode.DISCRETE_ONLY, true, false));
        assertThrows(IllegalStateException.class, () -> policy.format(surround, AudioOutputMode.DISCRETE_ONLY, false, false));
        var rejected = new OpenAlFailure(OpenAlFailure.Kind.OPERATION, "alBufferData", 0xA002, 17, 2);
        for (int i = 0; i < 3; i++) policy.rejected(rejected, surround);
        assertThrows(IllegalStateException.class, () -> policy.format(surround, AudioOutputMode.DISCRETE_ONLY, true, false));
        int stereo = OpenAlFormatSelector.select(2, false);
        assertEquals(stereo, policy.format(stereo, AudioOutputMode.DISCRETE_ONLY, false, false));
        assertEquals(AudioOutputMode.DISCRETE_ONLY, AudioOutputMode.parse(" discrete_only "));
    }
}
