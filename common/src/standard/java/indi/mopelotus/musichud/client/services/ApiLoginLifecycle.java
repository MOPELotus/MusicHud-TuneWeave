package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.server.api.ApiServerManager;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ApiLoginLifecycle {
    private ApiLoginLifecycle() {}

    // The returned task belongs to the client tick, never to the API worker.
    static Runnable register(BooleanSupplier ready, Runnable restore) {
        return new Runnable() {
            private final ApiLoginRestoreGate gate = new ApiLoginRestoreGate();
            private ApiServerManager observed;
            private Consumer<ApiServerManager.BinaryApiServerStatus> listener;

            @Override public void run() {
                ApiServerManager manager = ApiServerManager.getInstance();
                if (manager != observed) {
                    if (observed != null) observed.getApiStatusListeners().remove(listener);
                    observed = manager;
                    if (manager == null) {
                        gate.bind(null, false);
                    } else {
                        // API transitions use this same lock. Subscribe and replay together,
                        // recording intent only: no Minecraft access or restore under the lock.
                        synchronized (manager) {
                            gate.bind(manager, manager.getBinaryApiServerStatus()
                                    == ApiServerManager.BinaryApiServerStatus.RUNNING);
                            listener = status -> gate.status(manager,
                                    status == ApiServerManager.BinaryApiServerStatus.RUNNING);
                            manager.getApiStatusListeners().add(listener);
                        }
                    }
                }
                gate.tick(ready, restore);
            }
        };
    }
}
