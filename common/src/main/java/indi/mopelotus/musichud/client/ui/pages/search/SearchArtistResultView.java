package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.ArtistCard;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedCardGrid;
import lombok.Getter;
import java.util.List;

public class SearchArtistResultView extends VirtualizedCardGrid<Artist> {
    @Getter private static SearchArtistResultView instance;
    private static final SearchResultBuffer<Artist> results = new SearchResultBuffer<>(Artist::getSourceRef);
    public SearchArtistResultView(Context context) {
        super(context, 140,
                210, item -> artistCard(context, item));
        instance = this; refresh();
    }
    public static void setResult(List<Artist> values) {
        results.replace(values); if (instance != null) instance.refresh();
    }
    public void refresh() { setItems(results.snapshot()); }
    public void append(List<Artist> page) { results.append(page); refresh(); }
    private static ArtistCard artistCard(Context context, Artist item) {
        var card = new ArtistCard(context); card.bindData(item); return card;
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); instance = this; refresh(); }
    @Override protected void onDetachedFromWindow() { if (instance == this) instance = null; super.onDetachedFromWindow(); }
}
