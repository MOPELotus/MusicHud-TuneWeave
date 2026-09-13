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
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveRadioStation;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.components.UrlImageView;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Consumer-facing radio station details and its current playback queue. */
public final class RadioDetailView extends LinearLayout {
    private final ScopedViewTasks tasks = ClientViewTasks.create();
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final TuneWeaveRadioStation source;
    private final TextView status;
    private final LinearLayout tracks;
    private final Button subscriptionButton;
    private TuneWeaveRadioStation station;

    public RadioDetailView(Context context, TuneWeaveRadioStation source) {
        super(context);
        this.source = source;
        this.station = source;
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
        TextView info = new TextView(context);
        info.setTextSize(Theme.TEXT_SIZE_NORMAL);
        info.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        info.setMaxLines(6);
        info.setText(summaryText(source));
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
        tracks = new LinearLayout(context);
        tracks.setOrientation(VERTICAL);
        scroll.addView(tracks, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tasks.attach(); refresh(); }
            @Override public void onViewDetachedFromWindow(View v) { tasks.detach(); }
        });
    }

    private record Content(TuneWeaveRadioStation station, List<MusicDetail> queue) {}
    private void refresh() {
        showStatus(I18n.get(MusicHud.MOD_ID + ".text.programs.loading"));
        tasks.<Content>load(progress -> {
            var loaded = source.reference().contains(":difm:") ? source : tuneWeave.loadRadioStationDetail(source.reference());
            return new Content(loaded, tuneWeave.loadRadioPlaybackQueue(loaded));
        }, content -> render(content.station(), content.queue()), error -> showStatus(message(error)));
    }

    private void render(TuneWeaveRadioStation loaded, List<MusicDetail> queue) {
        station = loaded;
        subscriptionButton.setText(I18n.get(MusicHud.MOD_ID
                + (loaded.subscribed() ? ".button.unsubscribe" : ".button.subscribe")));
        status.setText(summaryText(loaded));
        tracks.removeAllViews();
        if (queue.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(I18n.get(MusicHud.MOD_ID + ".text.programs.emptyTracks"));
            empty.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            tracks.addView(empty, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }
        for (MusicDetail track : queue) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(ButtonInsetBackgroundFactory.builder().cornerRadius(dp(6)).inset(dp(1))
                    .build().newBackgroundDrawable());
            TextView name = new TextView(getContext());
            name.setText(track.getName());
            name.setTextSize(Theme.TEXT_SIZE_NORMAL);
            name.setTextColor(Theme.EMPHASIZE_TEXT_COLOR);
            name.setMaxLines(2);
            row.addView(name, new LayoutParams(0, WRAP_CONTENT, 1));
            row.addView(action(".button.play", v -> MusicService.getInstance().sendPushMusicToQueue(track)),
                    new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            LayoutParams params = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(6));
            tracks.addView(row, params);
        }
    }

    private void toggleSubscription() {
        var target = station;
        if (target == null) return;
        if (tasks.mutate(tasks.capture(), () -> tuneWeave.setRadioStationSubscribed(target, !target.subscribed()),
                this::refresh, error -> showStatus(message(error))))
            showStatus(I18n.get(MusicHud.MOD_ID + ".text.programs.saving"));
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

    private static String summaryText(TuneWeaveRadioStation value) {
        StringBuilder text = new StringBuilder();
        if (!value.category().isBlank()) text.append(value.category());
        if (!value.region().isBlank()) {
            if (!text.isEmpty()) text.append("  ·  ");
            text.append(value.region());
        }
        if (!value.currentProgram().isBlank()) {
            if (!text.isEmpty()) text.append("\n");
            text.append(value.currentProgram());
        }
        if (!value.description().isBlank()) {
            if (!text.isEmpty()) text.append("\n");
            text.append(value.description());
        }
        return text.toString();
    }

    private void showStatus(String value) { status.setText(value == null ? "" : value); }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
