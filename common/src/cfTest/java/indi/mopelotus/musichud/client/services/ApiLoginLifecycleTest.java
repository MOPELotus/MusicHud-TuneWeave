package indi.mopelotus.musichud.client.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class ApiLoginLifecycleTest {
    @Test void externalEditionDoesNotInspectClientOrRestoreOnEmbeddedServiceTicks() {
        Runnable tick = ApiLoginLifecycle.register(
                () -> { fail("External edition has no embedded service lifecycle"); return true; },
                () -> fail("External login remains owned by the normal join/login path"));
        tick.run();
        tick.run();
    }
}
