package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeavePodcast;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;
import lombok.Getter;

import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Search result list for podcast catalogs returned by the RADIO search type. */
public final class SearchPodcastResultView extends LinearLayout {
    @Getter
    private static SearchPodcastResultView instance;
    private static final SearchResultBuffer<TuneWeavePodcast> results = new SearchResultBuffer<>(TuneWeavePodcast::reference);

    public SearchPodcastResultView(Context context) {
        super(context);
        instance = this;
        setOrientation(VERTICAL);
        refresh();
    }

    public static void setResult(List<TuneWeavePodcast> value) {
        results.replace(value);
        if (instance != null) instance.refresh();
    }

    public void refresh() {
        List<TuneWeavePodcast> result = results.snapshot();
        removeAllViews();
        if (result != null) for (TuneWeavePodcast podcast : result) addPodcast(podcast);
    }

    public void append(List<TuneWeavePodcast> page) {
        for (TuneWeavePodcast item : results.append(page)) addPodcast(item);
    }

    private void addPodcast(TuneWeavePodcast podcast) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(8)).inset(dp(1))
                .build().newBackgroundDrawable());
        UrlImageView cover = new UrlImageView(getContext());
        cover.setCornerRadius(dp(6));
        cover.loadUrl(podcast.coverUrl().isBlank() ? MusicHud.ICON_BASE64 : podcast.coverUrl());
        row.addView(cover, new LayoutParams(dp(56), dp(56)));
        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(VERTICAL);
        TextView title = new TextView(getContext());
        title.setText(podcast.name());
        title.setTextSize(Theme.TEXT_SIZE_NORMAL);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setMaxLines(2);
        info.addView(title, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView meta = new TextView(getContext());
        meta.setText((podcast.creatorName().isBlank() ? "" : podcast.creatorName() + "  ·  ")
                + I18n.get(MusicHud.MOD_ID + ".text.programs.episodes").replace("{}", Long.toString(podcast.episodeCount())));
        meta.setTextSize(Theme.TEXT_SIZE_SMALL);
        meta.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        info.addView(meta, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams infoParams = new LayoutParams(0, WRAP_CONTENT, 1);
        infoParams.setMargins(dp(12), 0, 0, 0);
        row.addView(info, infoParams);
        row.setOnClickListener(v -> RouterContainer.getInstance().pushNavigate(
                new indi.mopelotus.musichud.client.ui.pages.PodcastDetailView(getContext(), podcast)));
        LayoutParams rowParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(6));
        addView(row, rowParams);
    }
}
