package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeavePodcast;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeavePodcastEpisode;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Consumer-facing podcast details. Episode publishing and editing are deliberately absent. */
public final class PodcastDetailView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final TuneWeavePodcast source;
    private final TextView status;
    private final TextView description;
    private final LinearLayout episodes;
    private final Button subscriptionButton;
    private TuneWeavePodcast podcast;
    private List<TuneWeavePodcastEpisode> episodeList = List.of();

    public PodcastDetailView(Context context, TuneWeavePodcast source) {
        super(context);
        this.source = source;
        this.podcast = source;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(action(".button.back", v -> RouterContainer.getInstance().popNavigate()),
                new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        TextView title = new TextView(context);
        title.setText(source.name());
        title.setTextSize(Theme.TEXT_SIZE_LARGER);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setMaxLines(2);
        toolbar.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        subscriptionButton = action(source.subscribed() ? ".button.unsubscribe" : ".button.subscribe",
                v -> toggleSubscription());
        toolbar.addView(subscriptionButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        toolbar.addView(action(".button.refresh", v -> refresh()), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(toolbar, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout summary = new LinearLayout(context);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        UrlImageView cover = new UrlImageView(context);
        cover.setCornerRadius(dp(8));
        cover.loadUrl(source.coverUrl().isBlank() ? MusicHud.ICON_BASE64 : source.coverUrl());
        summary.addView(cover, new LayoutParams(dp(88), dp(88)));
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(VERTICAL);
        TextView meta = new TextView(context);
        meta.setTextSize(Theme.TEXT_SIZE_SMALL);
        meta.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        meta.setText(metaText(source));
        info.addView(meta, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        description = new TextView(context);
        description.setTextSize(Theme.TEXT_SIZE_NORMAL);
        description.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        description.setMaxLines(5);
        description.setText(source.description());
        info.addView(description, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams infoParams = new LayoutParams(0, WRAP_CONTENT, 1);
        infoParams.setMargins(dp(14), 0, 0, 0);
        summary.addView(info, infoParams);
        LayoutParams summaryParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        summaryParams.setMargins(0, dp(14), 0, dp(8));
        addView(summary, summaryParams);

        status = new TextView(context);
        status.setTextSize(Theme.TEXT_SIZE_SMALL);
        status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        addView(status, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        episodes = new LinearLayout(context);
        episodes.setOrientation(VERTICAL);
        scroll.addView(episodes, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private record Content(TuneWeavePodcast podcast, List<TuneWeavePodcastEpisode> episodes) {}
    private void refresh() {
        boolean refresh = !tasks.failed();
        showStatus(I18n.get(MusicHud.MOD_ID + ".text.programs.loading"));
        tasks.<Content>load(progress -> {
            var loaded = tuneWeave.loadPodcastDetail(source.reference());
            var episodes = tuneWeave.loadPodcastEpisodes(loaded, refresh, values -> progress.accept(new Content(loaded, values)));
            return new Content(loaded, episodes);
        }, content -> render(content.podcast(), content.episodes()), error -> showStatus(message(error)));
    }

    private void render(TuneWeavePodcast loaded,
                        List<TuneWeavePodcastEpisode> loadedEpisodes) {
        podcast = loaded;
        episodeList = List.copyOf(loadedEpisodes);
        subscriptionButton.setText(I18n.get(MusicHud.MOD_ID
                + (loaded.subscribed() ? ".button.unsubscribe" : ".button.subscribe")));
        description.setText(loaded.description());
        status.setText(metaText(loaded));
        episodes.removeAllViews();
        if (episodeList.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(I18n.get(MusicHud.MOD_ID + ".text.programs.emptyEpisodes"));
            empty.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            episodes.addView(empty, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }
        for (TuneWeavePodcastEpisode episode : episodeList) addEpisode(episode);
    }

    private void addEpisode(TuneWeavePodcastEpisode episode) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(6)).inset(dp(1))
                .build().newBackgroundDrawable());
        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(VERTICAL);
        TextView title = new TextView(getContext());
        title.setText((episode.serialNumber() > 0 ? episode.serialNumber() + ". " : "") + episode.name());
        title.setTextSize(Theme.TEXT_SIZE_NORMAL);
        title.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
        title.setMaxLines(2);
        text.addView(title, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView meta = new TextView(getContext());
        meta.setText(formatDuration(episode.durationMillis()) + (episode.publishedAt().isBlank() ? "" : "  " + episode.publishedAt()));
        meta.setTextSize(Theme.TEXT_SIZE_SMALL);
        meta.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        text.addView(meta, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams textParams = new LayoutParams(0, WRAP_CONTENT, 1);
        textParams.setMargins(0, 0, dp(8), 0);
        row.addView(text, textParams);
        row.addView(action(".button.details", v -> loadEpisodeDetails(episode)), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        row.addView(action(".button.play", v -> play(episode)), new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        LayoutParams rowParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(6));
        episodes.addView(row, rowParams);
    }

    private record EpisodeContent(TuneWeavePodcastEpisode episode, indi.mopelotus.musichud.beans.music.LyricInfo lyrics) {}
    private void loadEpisodeDetails(TuneWeavePodcastEpisode episode) {
        var parent = podcast;
        tasks.<EpisodeContent>read(() -> {
            var detail = tuneWeave.loadPodcastEpisodeDetail(episode.reference());
            var track = tuneWeave.podcastEpisodeTrack(parent, detail);
            return new EpisodeContent(detail, detail.hasLyrics() ? tuneWeave.loadLyrics(track) : null);
        }, result -> showEpisodeDetails(result.episode(), result.lyrics()), error -> showStatus(message(error)));
    }

    private void showEpisodeDetails(TuneWeavePodcastEpisode episode,
                                    indi.mopelotus.musichud.beans.music.LyricInfo lyrics) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        TextView body = new TextView(getContext());
        body.setText(episode.description().isBlank() ? I18n.get(MusicHud.MOD_ID + ".text.programs.noDescription") : episode.description());
        body.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        body.setTextSize(Theme.TEXT_SIZE_NORMAL);
        body.setMaxLines(12);
        content.addView(body, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        if (lyrics != null) {
            String advanced = lyrics.getWordByWordLyric().getLyric();
            String lyric = !advanced.isBlank() && !advanced.stripLeading().startsWith("{")
                    ? advanced : lyrics.getLyric().getLyric();
            if (!lyric.isBlank()) {
                TextView lyricView = new TextView(getContext());
                lyricView.setText("\n" + lyric);
                lyricView.setTextColor(Theme.NORMAL_TEXT_COLOR);
                lyricView.setTextSize(Theme.TEXT_SIZE_SMALL);
                content.addView(lyricView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            }
        }
        new Modal(getContext(),
                title(episode.name()), content,
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.close"), (b, modal) -> modal.dismiss()),
                new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".button.play"), (b, modal) -> {
                    play(episode);
                    modal.dismiss();
                })).show();
        showStatus("");
    }

    private void play(TuneWeavePodcastEpisode episode) {
        MusicService.getInstance().sendPushMusicToQueue(tuneWeave.podcastEpisodeTrack(podcast, episode));
    }

    private void toggleSubscription() {
        var target = podcast;
        if (target == null) return;
        if (tasks.mutate(tasks.capture(), () -> tuneWeave.setPodcastSubscribed(target, !target.subscribed()),
                this::refresh, error -> showStatus(message(error))))
            showStatus(I18n.get(MusicHud.MOD_ID + ".text.programs.saving"));
    }

    private TextView title(String text) {
        TextView title = new TextView(getContext());
        title.setText(text);
        return title;
    }

    private Button action(String key, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(I18n.get(MusicHud.MOD_ID + key));
        button.setTextSize(Theme.TEXT_SIZE_SMALL);
        button.setTextColor(Theme.PRIMARY_COLOR);
        button.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(4)).inset(dp(1))
                .build().newBackgroundDrawable());
        var binding = tasks.capture();
        button.setOnClickListener(view -> {
            if (key.endsWith(".back") || key.endsWith(".refresh")
                    || (binding == null ? tasks.isCurrent() : tasks.isCurrent(binding))) listener.onClick(view);
        });
        return button;
    }

    private static String metaText(TuneWeavePodcast value) {
        List<String> parts = new java.util.ArrayList<>();
        if (!value.creatorName().isBlank()) parts.add(value.creatorName());
        if (!value.category().isBlank()) parts.add(value.category());
        parts.add(I18n.get(MusicHud.MOD_ID + ".text.programs.episodes")
                .replace("{}", Long.toString(value.episodeCount())));
        return String.join("  ·  ", parts);
    }

    private void showStatus(String value) { status.setText(value == null ? "" : value); }

    private static String formatDuration(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
