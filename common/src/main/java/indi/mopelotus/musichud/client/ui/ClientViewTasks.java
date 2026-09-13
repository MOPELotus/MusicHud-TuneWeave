package indi.mopelotus.musichud.client.ui;

import icyllis.modernui.mc.MuiModApi;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;

public final class ClientViewTasks {
    private ClientViewTasks() {}
    public static ScopedViewTasks create() {
        return new ScopedViewTasks(MusicHud.EXECUTOR, MuiModApi::postToUiThread,
                MusicEntityCache::captureGeneration, TuneWeaveClientService.getInstance()::prepareViewRequest);
    }
}
