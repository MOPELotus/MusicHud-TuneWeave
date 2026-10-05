package indi.mopelotus.musichud.client.update;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CfUpdatePolicyTest {
    @Test void directAndRepeatedDownloadRequestsCannotStartInstallation() {
        List<Runnable> work = new ArrayList<>(); int[] operations = {0};
        var offer = new ClientUpdateCatalog.Offer("v1.4.0-beta.1", "1.4.0-beta.1-cf.1+26.3", "cf", "fabric", List.of("26.3"), "fixture.jar", "a".repeat(64), 1, "");
        var service = new ClientUpdateService(work::add, new ClientUpdateService.Backend() {
            public Optional<ClientUpdateCatalog.Offer> check() { return Optional.of(offer); }
            public void stage(ClientUpdateCatalog.Offer value) { operations[0]++; }
            public void cleanSuccessfulUpdate() { operations[0]++; }
        });
        service.check(); work.removeFirst().run();
        service.download(); service.download(); service.clientStartedSuccessfully();
        assertTrue(work.isEmpty()); assertEquals(0, operations[0]);
        assertEquals(ClientUpdateService.Status.AVAILABLE, service.state().status());
        assertEquals(offer, service.state().offer());
    }
    @Test void distributionCannotEnableInstallationOrLoadItsClasses() {
        assertFalse(UpdateInstallation.enabled());
        assertThrows(UnsupportedOperationException.class, () -> UpdateInstallation.stage(null, null, null));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("indi.mopelotus.musichud.client.update.UpdateInstaller"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("indi.mopelotus.musichud.client.update.UpdateBackupCleanup"));
    }
}
