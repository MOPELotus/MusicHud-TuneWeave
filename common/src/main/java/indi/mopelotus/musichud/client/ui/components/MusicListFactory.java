package indi.mopelotus.musichud.client.ui.components;

import indi.mopelotus.musichud.client.ui.QueueSubmissionFeedback;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;

import java.util.function.Function;

public class MusicListFactory {
    public static MusicListItem createItem(ViewGroup parent) {
        return createItem(parent, (view) -> false);
    }
    public static MusicListItem createItem(ViewGroup parent, Function<View, Boolean> clickFilter) {
        MusicListItem item = new MusicListItem(parent.getContext());
        item.setRowAnimationsEnabled(false);
        item.setShowPusherInfo(false);
        if (item.getBackground() == null) {
            InsetBackgroundFactory.builder()
                    .cornerRadius(item.dp(7))
                    .inset(item.dp(1))
                    .padding(new InsetBackgroundFactory.Padding(item.dp(4), item.dp(4), item.dp(4), item.dp(4)))
                    .build()
                    .applyBackgroundTo(item);
        }
        item.setClickable(true);
        Context context = parent.getContext();
        item.setOnClickListener(view -> {
            if (clickFilter.apply(view)) return;
            MusicDetail musicDetail = item.getMusicDetail();
            if (musicDetail == null) return;
            QueueSubmissionFeedback.submit(view, musicDetail);
        });
        return item;
    }
}
