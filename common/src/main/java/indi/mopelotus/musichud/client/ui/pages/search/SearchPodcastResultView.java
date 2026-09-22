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
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.CollectionPreviewCard;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;
import lombok.Getter;

import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Search result list for podcast catalogs returned by the RADIO search type. */
public final class SearchPodcastResultView extends FlexWrapLayout {
    @Getter
    private static SearchPodcastResultView instance;
    private static final SearchResultBuffer<TuneWeavePodcast> results = new SearchResultBuffer<>(TuneWeavePodcast::reference);

    public SearchPodcastResultView(Context context) {
        super(context);
        instance = this;
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
        Object account = indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration();
        CollectionPreviewCard card = new CollectionPreviewCard(getContext(), podcast.coverUrl(), podcast.name(),
                podcast.description(), I18n.get(MusicHud.MOD_ID + ".text.programs.episodes")
                .replace("{}", Long.toString(podcast.episodeCount())));
        card.setOnClickListener(v -> {
            if (account != indi.mopelotus.musichud.client.services.music.MusicEntityCache.captureGeneration()) return;
            RouterContainer router = RouterContainer.getInstance();
            if (router != null) router.pushNavigate(new indi.mopelotus.musichud.client.ui.pages.PodcastDetailView(getContext(), podcast));
        });
        addView(card);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        instance = this;
        refresh();
    }
    @Override protected void onDetachedFromWindow() {
        if (instance == this) instance = null;
        super.onDetachedFromWindow();
    }
}
