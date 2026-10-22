package indi.mopelotus.musichud.client.audio;

/** Move legacy Dynamic FPS master ducking from the shared listener to vanilla sources. */
public final class DynamicFpsMasterGain {
    private volatile float multiplier = 1;

    public float listenerGain(float configuredMaster, float duckedMaster) {
        float configured = unit(configuredMaster);
        multiplier = configured > 0 ? unit(duckedMaster / configured) : 1;
        return configured;
    }

    public float vanillaSourceGain(float gain) {
        return gain * multiplier;
    }

    private static float unit(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
    }
}
