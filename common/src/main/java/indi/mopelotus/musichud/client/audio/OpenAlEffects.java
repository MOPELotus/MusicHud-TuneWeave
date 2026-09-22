package indi.mopelotus.musichud.client.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

/** Upstream dry-music behavior, restricted to live sources owned by this device epoch. */
public final class OpenAlEffects {
    private OpenAlEffects() {}

    public static void clearExternalEffects() {
        SoundEngineEpoch.guarded(() -> {
            if (!SoundEngineEpoch.available()) return null;
            long context = ALC10.alcGetCurrentContext();
            if (context == 0 || !AL.getCapabilities().ALC_EXT_EFX) return null;
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0 || ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS) <= 0) return null;
            OwnedAudioSources.INSTANCE.forEach(context, source -> {
                if (!AL10.alIsSource(source)) return;
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, 0, 0, 0);
                AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO, AL10.AL_FALSE);
                AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, 0);
                AL10.alGetError(); // Do not leak best-effort compatibility errors into decoder checks.
            });
            return null;
        });
    }
}
