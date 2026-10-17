package indi.mopelotus.musichud.client.services;

final class ApiLoginLifecycle {
    private ApiLoginLifecycle() {}
    static void register(Runnable restore) {
        var manager = indi.mopelotus.musichud.server.api.ApiServerManager.getInstance();
        if (manager != null) manager.getApiStatusListeners().add(status -> {
            if (status == indi.mopelotus.musichud.server.api.ApiServerManager.BinaryApiServerStatus.RUNNING) restore.run();
        });
    }
}
