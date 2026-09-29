package indi.mopelotus.musichud.client.ui.pages.account;
import org.junit.jupiter.api.Test;
import java.util.Set;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import static org.junit.jupiter.api.Assertions.*;
class AccountCapabilitiesTest {
    @Test void bilibiliPersonalFoldersUsePlaylistReadNotAccountPlaylists() {
        assertTrue(AccountCapabilities.hasPlaylists(TuneWeavePlatform.BILIBILI,Set.of("qr_login","account_profile","playlist_read")));
        assertFalse(AccountCapabilities.hasPlaylists(TuneWeavePlatform.BILIBILI,Set.of("account_profile")));
        assertFalse(AccountCapabilities.hasPlaylists(TuneWeavePlatform.QQ,Set.of("playlist_read")));
        assertTrue(AccountCapabilities.hasPlaylists(TuneWeavePlatform.QQ,Set.of("account_playlists")));
    }
}
