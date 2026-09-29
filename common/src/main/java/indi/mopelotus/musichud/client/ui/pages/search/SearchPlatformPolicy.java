package indi.mopelotus.musichud.client.ui.pages.search;

import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The server's registered capabilities determine which search pages can issue requests. */
final class SearchPlatformPolicy {
    private SearchPlatformPolicy() {}
    static List<SearchType> types(Set<String> capabilities, TuneWeavePlatform platform) {
        var result = new ArrayList<SearchType>();
        if (capabilities.contains(platform == TuneWeavePlatform.BILIBILI ? "search_videos" : "search_tracks"))
            result.add(SearchType.MUSIC);
        if (capabilities.contains("search_playlists")) result.add(SearchType.PLAYLIST);
        if (capabilities.contains("search_albums")) result.add(SearchType.ALBUM);
        if (capabilities.contains("search_artists")) result.add(SearchType.ARTIST);
        if (capabilities.contains("search_podcasts")) result.add(SearchType.RADIO);
        return List.copyOf(result);
    }
}
