package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.ArtistCard;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedCardGrid;
import lombok.Getter;
import java.util.List;

public class SearchPlaylistResultView extends VirtualizedCardGrid<Playlist> {
    @Getter private static SearchPlaylistResultView instance;
    private static final SearchResultBuffer<Playlist> results = new SearchResultBuffer<>(Playlist::getSourceRef);
    public SearchPlaylistResultView(Context context) {
        super(context, 174,
                280, item -> new MusicCollectionCard(context, item));
        instance = this; refresh();
    }
    public static void setResult(List<Playlist> values) {
        results.replace(values); if (instance != null) instance.refresh();
    }
    public void refresh() { setItems(results.snapshot()); }
    public void append(List<Playlist> page) { results.append(page); refresh(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); instance = this; refresh(); }
    @Override protected void onDetachedFromWindow() { if (instance == this) instance = null; super.onDetachedFromWindow(); }
}
