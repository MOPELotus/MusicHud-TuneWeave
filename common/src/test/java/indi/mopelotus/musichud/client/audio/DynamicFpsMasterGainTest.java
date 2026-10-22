package indi.mopelotus.musichud.client.audio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicFpsMasterGainTest {
    @Test void preservesVanillaDuckingWithoutDuckingIndependentSources() {
        var policy = new DynamicFpsMasterGain();
        for (float multiplier : new float[]{1, .5f, 0, .1f, 1}) {
            float listener = policy.listenerGain(.8f, .8f * multiplier);
            assertEquals(.8f, listener);
            assertEquals(.8f * multiplier * .3f, listener * policy.vanillaSourceGain(.3f), 1e-6);
            assertEquals(.16f, listener * .2f, 1e-6); // Independent music retains its own gain.
        }
    }

    @Test void manualMasterMuteAndUnmuteRemainEffective() {
        var policy = new DynamicFpsMasterGain();
        assertEquals(0, policy.listenerGain(0, 0));
        assertTrue(Float.isFinite(policy.vanillaSourceGain(.5f)));
        assertEquals(.5f, policy.listenerGain(.5f, .125f));
        assertEquals(.125f, policy.vanillaSourceGain(.5f));
        assertEquals(.5f, policy.listenerGain(.5f, .5f));
        assertEquals(.5f, policy.vanillaSourceGain(.5f));
    }

    @Test void inactiveCompatibilityIsNeutralAndMalformedGainsAreBounded() {
        var policy = new DynamicFpsMasterGain();
        assertEquals(.4f, policy.vanillaSourceGain(.4f));
        assertEquals(0, policy.listenerGain(Float.NaN, 0));
        assertEquals(1, policy.listenerGain(2, 2));
        assertEquals(.4f, policy.vanillaSourceGain(.4f));
        policy.listenerGain(.5f, Float.NaN);
        assertEquals(0, policy.vanillaSourceGain(.4f));
    }
}
