package indi.mopelotus.musichud.client.ui.components;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.List;
/** Presentation order is independent of persistent enum identity. */
public final class PlatformDisplayOrder {
    private PlatformDisplayOrder() {}
    public static List<TuneWeavePlatform> platforms() {
        return List.of(TuneWeavePlatform.NETEASE, TuneWeavePlatform.QQ, TuneWeavePlatform.BILIBILI,
                TuneWeavePlatform.KUGOU, TuneWeavePlatform.KUWO, TuneWeavePlatform.MIGU, TuneWeavePlatform.SODA);
    }
}
