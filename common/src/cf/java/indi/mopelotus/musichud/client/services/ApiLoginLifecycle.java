package indi.mopelotus.musichud.client.services;

import java.util.function.BooleanSupplier;

final class ApiLoginLifecycle {
    private ApiLoginLifecycle() {}
    static Runnable register(BooleanSupplier ready, Runnable restore) {
        // External service availability is handled by the normal login and join paths.
        return () -> {};
    }
}
