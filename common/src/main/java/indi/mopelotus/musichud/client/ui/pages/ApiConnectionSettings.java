package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;

final class ApiConnectionSettings {
    private ApiConnectionSettings() {}
    static boolean apply(String value, ClientConfig client, ServerConfig server) {
        final String normalized;
        try { normalized = TuneWeaveApiClient.normalizeBaseUrl(value); }
        catch (TuneWeaveApiClient.TuneWeaveException invalid) { return false; }
        client.setTuneWeaveBaseUrl(normalized);
        server.setServerApiBaseUrl(normalized);
        return true;
    }
}
