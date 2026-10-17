package indi.mopelotus.musichud.client.ui.components;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformDropdownTest {
    @Test void restoresEveryPlatformUsingTheDisplayedRowInsteadOfEnumOrdinal() {
        var dropdown = new PlatformDropdown(new LayoutTestContext());
        for (var platform : TuneWeavePlatform.values()) {
            dropdown.setSelectedPlatform(platform);
            assertEquals(platform, dropdown.getSelectedPlatform());
            assertEquals(platform, dropdown.getAdapter().getItem(dropdown.getSelectedItemPosition()));
        }
        assertNotEquals(TuneWeavePlatform.SODA.ordinal(), PlatformDisplayOrder.platforms().indexOf(TuneWeavePlatform.SODA));
    }
}
