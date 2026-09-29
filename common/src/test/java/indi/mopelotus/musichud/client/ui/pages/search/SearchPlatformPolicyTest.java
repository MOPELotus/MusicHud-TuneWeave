package indi.mopelotus.musichud.client.ui.pages.search;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SearchPlatformPolicyTest {
    @Test void unavailableTypesNeverGetPagesAndBilibiliUsesVideoCapability() {
        assertEquals(List.of(), SearchPlatformPolicy.types(Set.of(), TuneWeavePlatform.MIGU));
        assertEquals(List.of(SearchType.MUSIC), SearchPlatformPolicy.types(Set.of("search_videos"), TuneWeavePlatform.BILIBILI));
        assertEquals(List.of(SearchType.PLAYLIST, SearchType.ARTIST), SearchPlatformPolicy.types(Set.of("search_playlists", "search_artists", "future_capability"), TuneWeavePlatform.KUWO));
        assertEquals(List.of(), SearchPlatformPolicy.types(Set.of("search_tracks"), TuneWeavePlatform.BILIBILI));
    }
}
