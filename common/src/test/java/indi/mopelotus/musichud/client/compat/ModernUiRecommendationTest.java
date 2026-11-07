package indi.mopelotus.musichud.client.compat;

import icyllis.modernui.fragment.FragmentFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModernUiRecommendationTest {
    @Test void hostCanRecreateRecommendationThroughItsActualFragmentFactory() {
        var type = ModernUiRecommendation.RecommendationFragment.class;
        var restored = new FragmentFactory().instantiate(type.getClassLoader(), type.getName());
        assertInstanceOf(type, restored);
    }
}
