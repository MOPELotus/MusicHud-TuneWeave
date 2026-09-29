package indi.mopelotus.musichud.client.utils.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringValueTest {
    @Test void retargetPreservesPositionAndVelocityAndSettlesAfterLongPause() {
        for (float damping : new float[]{.75f,1f,2f}) {
            var spring = new SpringValue(.6f,damping); spring.set(0,0,100,1);
            spring.update(100_000_001); float position = spring.getValue(), velocity = spring.getVelocity();
            spring.setTarget(-20,100_000_001); assertEquals(position,spring.getValue()); assertEquals(velocity,spring.getVelocity());
            assertEquals(-20,spring.update(3_600_000_000_001L),.01); assertTrue(spring.isSettled());
        }
    }
    @Test void changingResponseRecomputesFrequenciesInEveryDampingRegime() {
        for(float damping : new float[]{.75f,1f,2f}) {
            var changed = new SpringValue(.6f,damping); changed.setResponse(.2f);
            var fresh = new SpringValue(.2f,damping);
            changed.set(0,3,10,1); fresh.set(0,3,10,1);
            assertEquals(fresh.update(123_000_001),changed.update(123_000_001),.00001);
            assertEquals(fresh.getVelocity(),changed.getVelocity(),.00001);
        }
    }
    @Test void translatingReferenceFrameDoesNotResetMotion() {
        var spring = new SpringValue(.6f,.75f); spring.set(0,0,20,1); spring.update(100_000_001);
        float position=spring.getValue(),velocity=spring.getVelocity(); spring.translate(7);
        assertEquals(position+7,spring.getValue(),.00001); assertEquals(velocity,spring.getVelocity());
        assertEquals(27,spring.update(10_000_000_001L),.01);
        assertThrows(IllegalArgumentException.class,()->spring.setResponse(Float.NaN));
        assertThrows(IllegalArgumentException.class,()->spring.setDamping(0));
    }
}
