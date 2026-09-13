package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL10;
import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OpenAlFormatSelectorTest {
    @Test void multichannelFormatsKeepCorrectFrameWidthsAndEnableDeviceVirtualization() {
        int[] channels = {4, 6, 7, 8};
        long[] masks = {0x33, 0x3f, 0x70f, 0x63f};
        int[] formats = {0x1205, 0x120b, 0x120e, 0x1211};
        for (int i = 0; i < channels.length; i++) for (boolean floating : new boolean[]{false, true}) {
            assertTrue(OpenAlFormatSelector.nativeMultichannelLayout(channels[i], masks[i], true));
            assertFalse(OpenAlFormatSelector.nativeMultichannelLayout(channels[i], masks[i], false));
            int format = OpenAlFormatSelector.select(channels[i], floating);
            assertEquals(formats[i] + (floating ? 1 : 0), format);
            assertEquals(channels[i] * (floating ? 4 : 2), OpenAlFormatSelector.frameSize(format));
            assertFalse(OpenAlFormatSelector.directChannels(format), "Do not drop channels absent on headphones");
        }
        assertFalse(OpenAlFormatSelector.nativeMultichannelLayout(6, 0x60f, true));
        assertTrue(OpenAlFormatSelector.directChannels(0x1103));
        assertThrows(IllegalArgumentException.class, () -> OpenAlFormatSelector.frameSize(-1));
    }
    @Test
    void integerPcmNeverGetsFloatFormat() {
        assertEquals(AL10.AL_FORMAT_STEREO16,
                OpenAlFormatSelector.select(AudioDecoder.SampleEncoding.PCM_S24_LE, 2, true));
    }
    @Test
    void selectsFloat32OnlyWhenExtensionIsPresent() {
        assertEquals(OpenAlFormatSelector.AL_FORMAT_STEREO_FLOAT32,
                OpenAlFormatSelector.select(2, OpenAlFormatSelector.supportsFloat32(Set.of("AL_EXT_FLOAT32"))));
        assertEquals(0x1103, OpenAlFormatSelector.select(2, false));
        assertFalse(OpenAlFormatSelector.supportsFloat32(Set.of("AL_EXT_MCFORMATS")));
    }

    @Test
    void rejectsUnsupportedChannelCount() {
        assertThrows(IllegalArgumentException.class, () -> OpenAlFormatSelector.select(3, true));
    }

    @Test
    void neverLabelsIntegerPcmAsFloat32() {
        assertEquals(0x1103, OpenAlFormatSelector.select(
                indi.mopelotus.musichud.client.audio.decoder.AudioDecoder.SampleEncoding.PCM_S16_LE,
                2, true));
    }
}
