package indi.mopelotus.musichud.server.api;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ServiceReleaseProfilesTest {
    private static final String MANIFEST = """
        {"schema_version":1,"project":"TuneWeave","api_version":"v1","version":"1.0.1","tag":"v1.0.1",
        "compatibility_profiles":["future-http-v2"],"artifacts":[{}]}
        """;
    @Test void olderExternalServiceUsesMatchingPublishedIdentityWithoutGrantingManagement() throws Exception {
        assertEquals(Set.of("future-http-v2"), ServiceReleaseProfiles.parsePublished("1.0.1", MANIFEST));
        assertThrows(java.io.IOException.class, () -> ServiceReleaseProfiles.parsePublished("1.0.2", MANIFEST));
        assertThrows(java.io.IOException.class, () -> ServiceReleaseProfiles.parsePublished("1.0.1", MANIFEST.replace("v1.0.1", "v1.0.2")));
        assertThrows(java.io.IOException.class, () -> ServiceReleaseProfiles.parsePublished("1.0.1", MANIFEST.replace("[\"future-http-v2\"]", "null")));
    }
    @Test void unknownHistoricalVersionAndInvalidUrlInputsCannotInventCompatibility() throws Exception {
        assertTrue(ServiceReleaseProfiles.parsePublished("1.0.1", MANIFEST.replace("\"compatibility_profiles\":[\"future-http-v2\"],", "")).isEmpty());
        for (String invalid : new String[]{"../escape", "1.0.1/other", "1.0.1?token=x", "v1.0.1", "1.0.1+other"})
            assertTrue(ServiceReleaseProfiles.forVersion(invalid).isEmpty());
    }
}
