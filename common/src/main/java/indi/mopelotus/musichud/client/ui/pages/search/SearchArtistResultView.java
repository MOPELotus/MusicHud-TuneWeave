package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.client.ui.components.ArtistCard;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import lombok.Getter;

import java.util.List;

public class SearchArtistResultView extends FlexWrapLayout {
    @Getter
    private static SearchArtistResultView instance;
    private static final SearchResultBuffer<Artist> results = new SearchResultBuffer<>(Artist::getSourceRef);

    public SearchArtistResultView(Context context) {
        super(context);
//        setRowMinWidth(dp(128));
        instance = this;
        refresh();
    }

    public static void setResult(List<Artist> result) {
        results.replace(result);
        if (instance != null) {
            instance.refresh();
        }
    }

    public void refresh() {
        List<Artist> result = results.snapshot();
        removeAllViews();
        if (result != null) {
            for (Artist artist : result) {
                addItem(getContext(), artist);
            }
        }
    }

    public void append(List<Artist> page) {
        for (Artist item : results.append(page)) addItem(getContext(), item);
    }

    private void addItem(Context context, Artist artist) {
        var artistListItem = new ArtistCard(context);
        artistListItem.bindData(artist);
        addView(artistListItem);
    }
}
