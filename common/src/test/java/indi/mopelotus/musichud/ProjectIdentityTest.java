package indi.mopelotus.musichud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectIdentityTest {
    @Test
    void independentIdentifiersStayInternallyConsistent() {
        assertEquals("musichud_tuneweave", ProjectIdentity.MOD_ID);
        assertEquals(ProjectIdentity.MOD_ID, ProjectIdentity.RESOURCE_NAMESPACE);
        assertEquals(ProjectIdentity.MOD_ID, MusicHud.MOD_ID);
        assertEquals(ProjectIdentity.PROJECT_ID, indi.mopelotus.musichud.network.ProtocolInfo.PROJECT_ID);
    }
}
