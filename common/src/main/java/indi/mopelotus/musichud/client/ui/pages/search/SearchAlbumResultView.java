package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.WaterfallLayout;
import lombok.Getter;

import java.util.List;

public class SearchAlbumResultView extends WaterfallLayout {
    @Getter
    private static SearchAlbumResultView instance;
    private static final SearchResultBuffer<Album> results = new SearchResultBuffer<>(Album::getSourceRef);

    public SearchAlbumResultView(Context context) {
        super(context);
        setRowMinWidth(dp(174));
        instance = this;
        refresh();
    }

    public static void setResult(List<Album> result) {
        results.replace(result);
        if (instance != null) {
            instance.refresh();
        }
    }

    public void refresh() {
        List<Album> result = results.snapshot();
        removeAllViews();
        if (result != null) {
            for (Album playlist : result) {
                addItem(getContext(), playlist);
            }
        }
    }

    public void append(List<Album> page) {
        for (Album item : results.append(page)) addItem(getContext(), item);
    }

    private void addItem(Context context, Album album) {
        MusicCollectionCard child = new MusicCollectionCard(context, album);
        addView(child);
    }
}