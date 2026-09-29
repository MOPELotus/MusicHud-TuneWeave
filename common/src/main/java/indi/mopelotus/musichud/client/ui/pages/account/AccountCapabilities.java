package indi.mopelotus.musichud.client.ui.pages.account;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.Set;

/** Bilibili personal folders use the existing user-playlist route, not account/playlists. */
final class AccountCapabilities {
    private AccountCapabilities() {}
    static boolean hasPlaylists(TuneWeavePlatform platform, Set<String> capabilities) {
        return platform == TuneWeavePlatform.BILIBILI ? capabilities.contains("playlist_read")
                : capabilities.contains("account_playlists") || capabilities.contains("favorites");
    }
}
