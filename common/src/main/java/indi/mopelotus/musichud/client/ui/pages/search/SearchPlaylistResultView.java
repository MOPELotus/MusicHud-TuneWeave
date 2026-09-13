package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.WaterfallLayout;
import lombok.Getter;

import java.util.List;

public class SearchPlaylistResultView extends WaterfallLayout {
    @Getter
    private static SearchPlaylistResultView instance;
    private static final SearchResultBuffer<Playlist> results = new SearchResultBuffer<>(Playlist::getSourceRef);

    public SearchPlaylistResultView(Context context) {
        super(context);
        instance = this;
        setRowMinWidth(dp(174));
        refresh();
    }

    public static void setResult(List<Playlist> result) {
        results.replace(result);
        if (instance != null) {
            instance.refresh();
        }
    }

    public void refresh() {
        List<Playlist> result = results.snapshot();
        removeAllViews();
        if (result != null) {
            for (Playlist playlist : result) {
                addItem(getContext(), playlist);
            }
        }
    }

    public void append(List<Playlist> page) {
        for (Playlist item : results.append(page)) addItem(getContext(), item);
    }

    private void addItem(Context context, Playlist playlist) {
        MusicCollectionCard child = new MusicCollectionCard(context, playlist);
        addView(child);
    }
}